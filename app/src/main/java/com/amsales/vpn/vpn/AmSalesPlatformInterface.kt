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
    @Volatile private var tunName: String = ""

    private val cm: ConnectivityManager =
        service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callbackRef = AtomicReference<ConnectivityManager.NetworkCallback?>(null)

    override fun openTun(options: TunOptions): Int {
        Log.i(TAG, "openTun: MTU=${options.mtu}, autoRoute=${options.autoRoute}")

        val builder = service.Builder()
            .setSession("AM.SALES VPN")
            .setMtu(options.mtu.coerceAtLeast(1280))

        val v4iter = options.inet4Address
        while (v4iter != null && v4iter.hasNext()) {
            val p = v4iter.next() ?: continue
            try { builder.addAddress(p.address(), p.prefix()) }
            catch (e: Exception) { Log.w(TAG, "addAddress v4: ${e.message}") }
        }
        val v6iter = options.inet6Address
        while (v6iter != null && v6iter.hasNext()) {
            val p = v6iter.next() ?: continue
            try { builder.addAddress(p.address(), p.prefix()) }
            catch (_: Exception) {}
        }

        var routesAdded = false
        val r4 = options.inet4RouteAddress
        while (r4 != null && r4.hasNext()) {
            val p = r4.next() ?: continue
            try { builder.addRoute(p.address(), p.prefix()); routesAdded = true }
            catch (_: Exception) {}
        }
        val r6 = options.inet6RouteAddress
        while (r6 != null && r6.hasNext()) {
            val p = r6.next() ?: continue
            try { builder.addRoute(p.address(), p.prefix()); routesAdded = true }
            catch (_: Exception) {}
        }
        if (!routesAdded) builder.addRoute("0.0.0.0", 0)

        try {
            val dns = options.dnsServerAddress
            if (dns != null && dns.value.isNotBlank()) builder.addDnsServer(dns.value)
            else { builder.addDnsServer("1.1.1.1"); builder.addDnsServer("8.8.8.8") }
        } catch (_: Exception) { builder.addDnsServer("1.1.1.1") }

        for (pkg in blacklistApps) {
            try { builder.addDisallowedApplication(pkg) }
            catch (e: Exception) { Log.w(TAG, "skip $pkg: ${e.message}") }
        }
        try { builder.addDisallowedApplication(service.packageName) } catch (_: Exception) {}

        val excl: StringIterator? = options.excludePackage
        while (excl != null && excl.hasNext()) {
            val pkg = excl.next() ?: continue
            try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
        }
        val incl: StringIterator? = options.includePackage
        while (incl != null && incl.hasNext()) {
            val pkg = incl.next() ?: continue
            try { builder.addAllowedApplication(pkg) } catch (_: Exception) {}
        }

        val pfd = builder.establish()
            ?: throw RuntimeException("VpnService.Builder.establish() = null — нет разрешения?")
        tunFd = pfd

        // Имя TUN — для exclude из getInterfaces. Ищем интерфейс с нашим адресом.
        tunName = try {
            java.net.NetworkInterface.getNetworkInterfaces().toList().firstOrNull { ni ->
                ni.inetAddresses.toList().any {
                    it.hostAddress == AmSalesVpnService.TUN_ADDRESS
                }
            }?.name ?: "tun0"
        } catch (_: Exception) { "tun0" }

        Log.i(TAG, "TUN установлен fd=${pfd.fd} имя=$tunName")
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
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun systemCertificates(): StringIterator? = null

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
        // SagerNet делает через ConnectivityManager.allNetworks.
        // Но для совместимости с Android < M используем JDK fallback.
        return try {
            val nets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) cm.allNetworks else null
            if (nets != null && nets.isNotEmpty()) {
                CmInterfaceIterator(cm, nets.toList(), tunName)
            } else {
                JdkInterfaceIterator(tunName)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "getInterfaces fallback: ${e.message}")
            JdkInterfaceIterator(tunName)
        }
    }

    private class CmInterfaceIterator(
        private val cm: ConnectivityManager,
        networks: List<Network>,
        private val excludeTun: String,
    ) : NetworkInterfaceIterator {

        private val pending: MutableList<NetworkInterface> = run {
            val out = mutableListOf<NetworkInterface>()
            for (net in networks) {
                try {
                    val lp = cm.getLinkProperties(net) ?: continue
                    val name = lp.interfaceName ?: continue
                    if (name == excludeTun || name.startsWith("tun")) continue
                    val ji = try { java.net.NetworkInterface.getByName(name) } catch (_: Exception) { null }
                    val addrs = lp.linkAddresses.mapNotNull { it.address.hostAddress }
                    val ni = NetworkInterface()
                    ni.index = ji?.index ?: 0
                    ni.mtu = try { ji?.mtu ?: 1500 } catch (_: Exception) { 1500 }
                    ni.name = name
                    ni.addresses = ListStringIterator(addrs)
                    ni.flags = 0
                    val caps = cm.getNetworkCapabilities(net)
                    ni.type = when {
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 0
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> 1
                        caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> 2
                        else -> 3
                    }
                    val dnsServers = lp.dnsServers.mapNotNull { it.hostAddress }
                    ni.dnsServer = ListStringIterator(dnsServers)
                    ni.metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                    out.add(ni)
                } catch (e: Exception) {
                    Log.w(TAG, "interface skip: ${e.message}")
                }
            }
            out
        }

        private val iter = pending.iterator()
        override fun hasNext(): Boolean = iter.hasNext()
        override fun next(): NetworkInterface = iter.next()
    }

    private class JdkInterfaceIterator(excludeTun: String) : NetworkInterfaceIterator {
        private val pending: MutableList<NetworkInterface> = run {
            val out = mutableListOf<NetworkInterface>()
            try {
                java.net.NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                    try {
                        if (!ni.isUp || ni.isLoopback) return@forEach
                        if (ni.name == excludeTun || ni.name.startsWith("tun")) return@forEach
                        val addrs = ni.inetAddresses.toList().mapNotNull { it.hostAddress }
                        if (addrs.isEmpty()) return@forEach
                        val o = NetworkInterface()
                        o.index = ni.index
                        o.mtu = try { ni.mtu } catch (_: Exception) { 1500 }
                        o.name = ni.name
                        o.addresses = ListStringIterator(addrs)
                        o.flags = 0
                        o.type = 3
                        o.dnsServer = ListStringIterator(emptyList())
                        o.metered = false
                        out.add(o)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            out
        }
        private val iter = pending.iterator()
        override fun hasNext(): Boolean = iter.hasNext()
        override fun next(): NetworkInterface = iter.next()
    }

    private class ListStringIterator(private val items: List<String>) : StringIterator {
        private val iter = items.iterator()
        override fun hasNext(): Boolean = iter.hasNext()
        override fun len(): Int = items.size
        override fun next(): String = iter.next()
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        listener ?: return

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                pushInterfaceUpdate(network, listener, "onAvailable")
            }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                pushInterfaceUpdate(network, listener, "onLink", lp = lp)
            }
            override fun onLost(network: Network) {
                Log.i(TAG, "default network lost")
                // SagerNet шлёт пустые значения при потере сети.
                try {
                    listener.updateDefaultInterface("", 0, false, false)
                } catch (t: Throwable) {
                    Log.w(TAG, "updateDefaultInterface(empty): ${t.message}")
                }
            }
        }

        try {
            cm.registerDefaultNetworkCallback(cb)
            callbackRef.set(cb)
            Log.i(TAG, "default-interface monitor started")
        } catch (e: Throwable) {
            Log.e(TAG, "registerDefaultNetworkCallback", e)
        }
    }

    /** SagerNet-стиль: retry до 10 раз с 100ms delay чтобы дождаться NetworkInterface. */
    private fun pushInterfaceUpdate(
        network: Network,
        listener: InterfaceUpdateListener,
        source: String,
        lp: LinkProperties? = null,
    ) {
        Thread {
            try {
                var name = ""
                var index = -1
                for (attempt in 0 until 10) {
                    try {
                        val linkProps = lp ?: cm.getLinkProperties(network)
                        name = linkProps?.interfaceName ?: ""
                        if (name.isNotEmpty()) {
                            val ji = java.net.NetworkInterface.getByName(name)
                            if (ji != null) {
                                index = ji.index
                                break
                            }
                        }
                    } catch (_: Throwable) {}
                    try { Thread.sleep(100) } catch (_: InterruptedException) {}
                }
                if (name.isEmpty() || index < 0) {
                    Log.w(TAG, "$source: failed to resolve interface after 10 retries")
                    return@Thread
                }

                val caps = try { cm.getNetworkCapabilities(network) } catch (_: Throwable) { null }
                val metered = try {
                    caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
                } catch (_: Throwable) { false }

                Log.i(TAG, "default interface: $name index=$index metered=$metered ($source)")
                try {
                    listener.updateDefaultInterface(name, index, metered, false)
                } catch (t: Throwable) {
                    Log.w(TAG, "updateDefaultInterface: ${t.message}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "pushInterfaceUpdate($source)", t)
            }
        }.start()
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        val cb = callbackRef.getAndSet(null) ?: return
        try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
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
