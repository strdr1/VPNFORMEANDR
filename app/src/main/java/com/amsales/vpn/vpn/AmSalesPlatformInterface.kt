package com.amsales.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * Реализация libbox.PlatformInterface — следует эталону sing-box-for-android.
 *
 * Главные паттерны:
 *  - useProcFS=true ТОЛЬКО на Android < Q (на Q+ procfs закрыт)
 *  - findConnectionOwner через ConnectivityManager.getConnectionOwnerUid (API 29+)
 *  - default-interface monitor с retry-логикой (100ms x 10)
 *  - getInterfaces через ConnectivityManager.allNetworks (а не JDK)
 */
class AmSalesPlatformInterface(
    private val service: AmSalesVpnService,
    private val blacklistApps: Set<String>,
) : PlatformInterface {

    @Volatile var tunFd: ParcelFileDescriptor? = null
        private set

    private val cm: ConnectivityManager =
        service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callbackRef = AtomicReference<ConnectivityManager.NetworkCallback?>(null)

    override fun openTun(options: TunOptions): Int {
        Log.i(TAG, "openTun: MTU=${options.mtu}, autoRoute=${options.autoRoute}")

        val builder = service.Builder()
            .setSession("AM.SALES VPN")
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        // Адреса всегда добавляем
        val v4it = options.inet4Address
        while (v4it != null && v4it.hasNext()) {
            val p = v4it.next() ?: continue
            try { builder.addAddress(p.address(), p.prefix()) }
            catch (e: Exception) { Log.w(TAG, "addAddress v4: ${e.message}") }
        }
        val v6it = options.inet6Address
        while (v6it != null && v6it.hasNext()) {
            val p = v6it.next() ?: continue
            try { builder.addAddress(p.address(), p.prefix()) }
            catch (_: Exception) {}
        }

        // ОСТАЛЬНОЕ — ТОЛЬКО ЕСЛИ autoRoute=true (как у SagerNet).
        // sing-box без autoRoute не ожидает наших routes/dns/packages.
        if (options.autoRoute) {
            try {
                val dns = options.dnsServerAddress
                if (dns != null && dns.value.isNotBlank()) builder.addDnsServer(dns.value)
            } catch (_: Exception) {}

            var r4Added = false
            val r4 = options.inet4RouteAddress
            while (r4 != null && r4.hasNext()) {
                val p = r4.next() ?: continue
                try { builder.addRoute(p.address(), p.prefix()); r4Added = true }
                catch (_: Exception) {}
            }
            if (!r4Added) builder.addRoute("0.0.0.0", 0)

            val r6 = options.inet6RouteAddress
            while (r6 != null && r6.hasNext()) {
                val p = r6.next() ?: continue
                try { builder.addRoute(p.address(), p.prefix()) }
                catch (_: Exception) {}
            }

            // includePackage от sing-box (Go-сторона)
            val incl: StringIterator? = options.includePackage
            while (incl != null && incl.hasNext()) {
                val pkg = incl.next() ?: continue
                try { builder.addAllowedApplication(pkg) } catch (_: Exception) {}
            }
            // excludePackage от sing-box
            val excl: StringIterator? = options.excludePackage
            while (excl != null && excl.hasNext()) {
                val pkg = excl.next() ?: continue
                try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
            }
            // Наш пользовательский blacklist
            for (pkg in blacklistApps) {
                try { builder.addDisallowedApplication(pkg) }
                catch (e: Exception) { Log.w(TAG, "skip $pkg: ${e.message}") }
            }
            // ВАЖНО: НЕ добавляем addDisallowedApplication(packageName) —
            // SagerNet этого не делает. Sing-box должен сам это сделать
            // через excludePackage если нужно.
        }

        val pfd = builder.establish()
            ?: throw RuntimeException("VpnService.Builder.establish() = null — нет разрешения?")
        tunFd = pfd

        Log.i(TAG, "TUN установлен fd=${pfd.fd}")
        return pfd.fd
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        // У SagerNet ровно так — protect(fd) от VpnService.
        try { service.protect(fd) } catch (e: Exception) {
            Log.w(TAG, "protect($fd): ${e.message}")
        }
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    // ЭТАЛОН SagerNet: procfs только на Android < Q.
    // На Q+ /proc/net закрыт для приложений и FindConnectionOwner идёт
    // через ConnectivityManager.getConnectionOwnerUid (см. ниже).
    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun includeAllNetworks(): Boolean = false
    override fun underNetworkExtension(): Boolean = false
    override fun clearDNSCache() {}
    override fun readWIFIState(): WIFIState? = null

    // Минимальный LocalDNSTransport — без него Go-сторона sing-box на
    // Android может крашиться SIGABRT при попытке резолва .ru-доменов
    // через local-сервер. Используем InetAddress.getAllByName (он сам
    // ходит через системный DNS поверх default network).
    override fun localDNSTransport(): LocalDNSTransport? = SimpleLocalDNS()

    private class SimpleLocalDNS : LocalDNSTransport {
        override fun raw(): Boolean = false  // мы не raw, мы по lookup-методу

        override fun exchange(ctx: ExchangeContext, message: ByteArray) {
            // raw()=false => этот метод не должен зваться, но на всякий случай.
            ctx.errorCode(2)  // SERVFAIL
        }

        override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
            try {
                val addrs = java.net.InetAddress.getAllByName(domain)
                val filtered = addrs.filter {
                    when (network) {
                        "ip4", "tcp4", "udp4" -> it is java.net.Inet4Address
                        "ip6", "tcp6", "udp6" -> it is java.net.Inet6Address
                        else -> true
                    }
                }
                if (filtered.isEmpty()) {
                    ctx.errorCode(3)  // NXDOMAIN
                    return
                }
                ctx.success(filtered.joinToString(" ") { it.hostAddress ?: "" })
            } catch (e: Exception) {
                ctx.errorCode(2)  // SERVFAIL
            }
        }
    }

    // Возвращаем пустой StringIterator (не null!) — Go-сторона может
    // делать .Next() на nil и крашиться.
    override fun systemCertificates(): StringIterator = ListStringIterator(emptyList())

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int,
    ): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw Exception("findConnectionOwner unavailable on Android < Q")
        }
        return findConnectionOwnerQ(ipProtocol, sourceAddress ?: "", sourcePort,
                                       destinationAddress ?: "", destinationPort)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findConnectionOwnerQ(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        try {
            val uid = cm.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(sourceAddress, sourcePort),
                InetSocketAddress(destinationAddress, destinationPort),
            )
            if (uid == Process.INVALID_UID) throw Exception("connection owner not found")
            val packages = service.packageManager.getPackagesForUid(uid)
            val owner = ConnectionOwner()
            owner.userId = uid
            owner.userName = packages?.firstOrNull() ?: ""
            // setAndroidPackageNames принимает StringIterator
            owner.setAndroidPackageNames(ListStringIterator(packages?.toList() ?: emptyList()))
            return owner
        } catch (e: Exception) {
            throw e
        }
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        // Жёстко по эталону SagerNet: ConnectivityManager.allNetworks +
        // JDK NetworkInterface для индекса/mtu/addresses-с-префиксом.
        // НЕ исключаем TUN — sing-box сам знает наше myTunName.
        val out = mutableListOf<NetworkInterface>()
        try {
            val networks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.allNetworks else null
            val allJdk = try { java.net.NetworkInterface.getNetworkInterfaces().toList() } catch (_: Exception) { emptyList<java.net.NetworkInterface>() }

            if (networks != null && networks.isNotEmpty()) {
                for (net in networks) {
                    try {
                        val lp = cm.getLinkProperties(net) ?: continue
                        val caps = cm.getNetworkCapabilities(net) ?: continue
                        val name = lp.interfaceName ?: continue
                        val ji = allJdk.find { it.name == name } ?: continue
                        out.add(buildLibboxInterface(ji, name, caps, lp))
                    } catch (e: Exception) {
                        Log.w(TAG, "iface skip: ${e.message}")
                    }
                }
            } else {
                // Fallback на JDK для Android < M
                for (ji in allJdk) {
                    try {
                        if (!ji.isUp || ji.isLoopback) continue
                        out.add(buildLibboxInterface(ji, ji.name, null, null))
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "getInterfaces: ${e.message}")
        }
        return SimpleIterator(out)
    }

    private fun buildLibboxInterface(
        ji: java.net.NetworkInterface,
        name: String,
        caps: NetworkCapabilities?,
        lp: LinkProperties?,
    ): NetworkInterface {
        val ni = NetworkInterface()
        ni.name = name
        ni.index = ji.index
        ni.mtu = try { ji.mtu } catch (_: Exception) { 1500 }
        // Адреса в формате "ip/prefix" — Go-side парсит как сетевой префикс.
        val addrs = ji.interfaceAddresses.mapNotNull {
            val a = it.address.hostAddress ?: return@mapNotNull null
            "$a/${it.networkPrefixLength}"
        }
        ni.addresses = ListStringIterator(addrs)
        // Type — по transport.
        ni.type = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 0   // WIFI
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> 1 // Cellular
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> 2 // Ethernet
            else -> 3  // Other
        }
        // Flags — комбинация IFF_UP/IFF_RUNNING/IFF_LOOPBACK/IFF_POINTOPOINT/IFF_MULTICAST.
        var flags = 0
        try {
            if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                flags = flags or android.system.OsConstants.IFF_UP or android.system.OsConstants.IFF_RUNNING
            } else if (ji.isUp) {
                flags = flags or android.system.OsConstants.IFF_UP or android.system.OsConstants.IFF_RUNNING
            }
            if (ji.isLoopback) flags = flags or android.system.OsConstants.IFF_LOOPBACK
            if (ji.isPointToPoint) flags = flags or android.system.OsConstants.IFF_POINTOPOINT
            if (ji.supportsMulticast()) flags = flags or android.system.OsConstants.IFF_MULTICAST
        } catch (_: Throwable) {}
        ni.flags = flags
        // DNS-серверы
        val dnsServers = lp?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
        ni.dnsServer = ListStringIterator(dnsServers)
        ni.metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        return ni
    }

    private class SimpleIterator(private val items: List<NetworkInterface>) : NetworkInterfaceIterator {
        private val iter = items.iterator()
        override fun hasNext(): Boolean = iter.hasNext()
        override fun next(): NetworkInterface = iter.next()
    }

    private class ListStringIterator(private val items: List<String>) : StringIterator {
        private val iter = items.iterator()
        override fun hasNext(): Boolean = iter.hasNext()
        override fun len(): Int = items.size
        override fun next(): String = iter.next()
    }

    // Сериализация всех вызовов listener.updateDefaultInterface — Go-side
    // sing-box не thread-safe для этого callback'а.
    private val updateLock = Any()
    @Volatile private var updaterThread: java.util.concurrent.ExecutorService? = null

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        listener ?: return

        // Создаём executor свежий — в connect()→disconnect() он мог
        // быть shutdown'ом, переиспользовать нельзя.
        updaterThread = java.util.concurrent.Executors.newSingleThreadExecutor()

        // ConnectivityManager.registerDefaultNetworkCallback при регистрации
        // СРАЗУ синхронно зовёт onAvailable. В этот момент Go ещё может не
        // быть готов — пропускаем первые 2с.
        val readyAt = System.currentTimeMillis() + 2000

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                queueUpdate(network, listener, readyAt, "onAvailable", null)
            }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                queueUpdate(network, listener, readyAt, "onLink", lp)
            }
            override fun onLost(network: Network) {
                Log.i(TAG, "default network lost")
                queueLost(listener, readyAt)
            }
        }

        try {
            cm.registerDefaultNetworkCallback(cb)
            callbackRef.set(cb)
            Log.i(TAG, "default-interface monitor started (ready in 2s)")
        } catch (e: Throwable) {
            Log.e(TAG, "registerDefaultNetworkCallback", e)
        }
    }

    private fun queueUpdate(
        network: Network,
        listener: InterfaceUpdateListener,
        readyAt: Long,
        source: String,
        lp: LinkProperties?,
    ) {
        // Все обновления выполняются на одном thread (single-threaded executor)
        // и сериализованы через synchronized(updateLock).
        try {
            updaterThread?.execute {
                try {
                    val waitFor = readyAt - System.currentTimeMillis()
                    if (waitFor > 0) {
                        Log.d(TAG, "$source: warmup wait ${waitFor}ms")
                        try { Thread.sleep(waitFor) } catch (_: InterruptedException) { return@execute }
                    }

                    var name = ""
                    var index = -1
                    for (attempt in 0 until 10) {
                        try {
                            val linkProps = lp ?: cm.getLinkProperties(network)
                            val n = linkProps?.interfaceName
                            if (!n.isNullOrEmpty()) {
                                val ji = java.net.NetworkInterface.getByName(n)
                                if (ji != null) {
                                    name = n
                                    index = ji.index
                                    break
                                }
                            }
                        } catch (_: Throwable) {}
                        try { Thread.sleep(100) } catch (_: InterruptedException) { return@execute }
                    }

                    if (name.isEmpty() || index <= 0) {
                        Log.w(TAG, "$source: cannot resolve interface")
                        return@execute
                    }

                    val caps = try { cm.getNetworkCapabilities(network) } catch (_: Throwable) { null }
                    val metered = try {
                        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                    } catch (_: Throwable) { false }

                    synchronized(updateLock) {
                        Log.i(TAG, "default: $name index=$index metered=$metered ($source)")
                        try {
                            listener.updateDefaultInterface(name, index, metered, false)
                        } catch (t: Throwable) {
                            Log.e(TAG, "updateDefaultInterface", t)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "queueUpdate($source)", t)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // executor closed
        }
    }

    private fun queueLost(listener: InterfaceUpdateListener, readyAt: Long) {
        try {
            updaterThread?.execute {
                try {
                    val waitFor = readyAt - System.currentTimeMillis()
                    if (waitFor > 0) try { Thread.sleep(waitFor) } catch (_: InterruptedException) { return@execute }
                    synchronized(updateLock) {
                        try {
                            listener.updateDefaultInterface("", 0, false, false)
                        } catch (t: Throwable) {
                            Log.e(TAG, "updateDefaultInterface(empty)", t)
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Exception) {}
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        val cb = callbackRef.getAndSet(null) ?: return
        try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        try { updaterThread?.shutdownNow() } catch (_: Exception) {}
        updaterThread = null
        Log.i(TAG, "default-interface monitor stopped")
    }

    override fun sendNotification(notification: Notification?) {
        notification ?: return
        try {
            Log.i(TAG, "sing-box notify: ${notification.title} — ${notification.subtitle}")
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "AmSalesPlatform"
    }
}
