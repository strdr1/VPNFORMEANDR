package com.amsales.vpn.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
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
import java.util.concurrent.atomic.AtomicReference

/**
 * Реализация libbox.PlatformInterface — мост между Java/Kotlin и Go-кодом
 * sing-box.
 *
 * Самый важный метод — openTun(options): здесь мы поднимаем
 * VpnService.Builder с параметрами от sing-box, делаем establish() и
 * возвращаем целочисленный fd, который Go-сторона использует как TUN.
 *
 * Также критично:
 *   - getInterfaces(): возвращает реальные сетевые интерфейсы Android
 *     (БЕЗ нашего TUN — иначе sing-box зациклится на нём как на upstream).
 *   - startDefaultInterfaceMonitor: подписывается на ConnectivityManager
 *     и сообщает sing-box какой интерфейс является default, чтобы
 *     auto_detect_interface работал. Без этого direct-outbound и TLS-DNS
 *     с detour=proxy не имеют upstream — трафик не идёт.
 */
class AmSalesPlatformInterface(
    private val service: AmSalesVpnService,
    private val blacklistApps: Set<String>,
) : PlatformInterface {

    @Volatile var tunFd: ParcelFileDescriptor? = null
        private set
    @Volatile private var tunName: String = "tun0"

    private val cm: ConnectivityManager? =
        service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val callbackRef = AtomicReference<ConnectivityManager.NetworkCallback?>(null)
    private val currentDefault = AtomicReference<DefaultInterface?>(null)

    private data class DefaultInterface(
        val name: String, val index: Int,
        val isExpensive: Boolean, val isConstrained: Boolean,
    )

    override fun openTun(options: TunOptions): Int {
        Log.i(TAG, "openTun: MTU=${options.mtu}, autoRoute=${options.autoRoute}")

        val builder = service.Builder()
            .setSession("AM.SALES VPN")
            .setMtu(options.mtu.coerceAtLeast(1280))

        // IPv4 addresses
        val v4iter = options.inet4Address
        while (v4iter != null && v4iter.hasNext()) {
            val p = v4iter.next() ?: continue
            try { builder.addAddress(p.address(), p.prefix()) }
            catch (e: Exception) { Log.w(TAG, "addAddress v4 failed: ${e.message}") }
        }
        val v6iter = options.inet6Address
        while (v6iter != null && v6iter.hasNext()) {
            val p = v6iter.next() ?: continue
            try { builder.addAddress(p.address(), p.prefix()) }
            catch (_: Exception) {}
        }

        // Routes: если sing-box передал — используем; иначе дефолт 0.0.0.0/0
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
        if (!routesAdded) {
            builder.addRoute("0.0.0.0", 0)
        }

        // DNS — используем фиксированный (системный потом перехватим
        // через hijack-dns правило в sing-box)
        try {
            val dns = options.dnsServerAddress
            if (dns != null && dns.value.isNotBlank()) {
                builder.addDnsServer(dns.value)
            } else {
                builder.addDnsServer("1.1.1.1")
                builder.addDnsServer("8.8.8.8")
            }
        } catch (_: Exception) {
            builder.addDnsServer("1.1.1.1")
        }

        // Split tunneling
        for (pkg in blacklistApps) {
            try { builder.addDisallowedApplication(pkg) }
            catch (e: Exception) { Log.w(TAG, "skip $pkg: ${e.message}") }
        }
        // ОБЯЗАТЕЛЬНО: сам себя — мимо туннеля. Без этого sing-box internal
        // сокеты замыкаются.
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
            ?: throw RuntimeException("VpnService.Builder.establish() вернул null — нет разрешения?")
        tunFd = pfd

        // Запомнили имя TUN-интерфейса чтобы исключить из getInterfaces.
        tunName = guessTunName(pfd.fd)

        Log.i(TAG, "TUN установлен, fd=${pfd.fd}, имя~$tunName")
        return pfd.fd
    }

    private fun guessTunName(fd: Int): String {
        // На Android VpnService туннели обычно называются tun0, tun1...
        // Точно определить нельзя без ioctl. Берём все имена которые
        // НЕ выглядят как обычные сетевые интерфейсы.
        return try {
            val all = java.net.NetworkInterface.getNetworkInterfaces().toList()
            // Ищем интерфейс с адресом 172.19.0.1 (наш TUN_ADDRESS)
            for (ni in all) {
                for (addr in ni.inetAddresses.toList()) {
                    if (addr.hostAddress == AmSalesVpnService.TUN_ADDRESS) {
                        return ni.name
                    }
                }
            }
            "tun0"
        } catch (_: Exception) { "tun0" }
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        // Защита сокета — без этого исходящий коннект к VLESS-серверу
        // зайдёт обратно в наш TUN и зациклится.
        try {
            val ok = service.protect(fd)
            if (!ok) Log.w(TAG, "service.protect($fd) returned false")
        } catch (e: Exception) {
            Log.w(TAG, "protect($fd) failed: ${e.message}")
        }
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun useProcFS(): Boolean = true

    override fun includeAllNetworks(): Boolean = false

    override fun underNetworkExtension(): Boolean = false

    override fun clearDNSCache() { /* no-op */ }

    override fun readWIFIState(): WIFIState? = null

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun systemCertificates(): StringIterator? = null

    override fun getInterfaces(): NetworkInterfaceIterator {
        return AndroidNetworkInterfaceIterator(tunName)
    }

    /** Перечисляет JDK NetworkInterface исключая наш TUN. */
    private class AndroidNetworkInterfaceIterator(private val excludeTun: String) : NetworkInterfaceIterator {
        private val iter: Iterator<java.net.NetworkInterface> = try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { ni ->
                    try {
                        ni.isUp
                            && ni.inetAddresses.toList().isNotEmpty()
                            && ni.name != excludeTun
                            && !ni.name.startsWith("tun")  // на всякий случай — все tun-ы
                            && !ni.isLoopback
                    } catch (_: Exception) { false }
                }?.iterator()
                ?: emptyList<java.net.NetworkInterface>().iterator()
        } catch (_: Exception) {
            emptyList<java.net.NetworkInterface>().iterator()
        }

        override fun hasNext(): Boolean = iter.hasNext()

        override fun next(): NetworkInterface {
            val ni = iter.next()
            val out = NetworkInterface()
            out.index = ni.index
            out.mtu = try { ni.mtu } catch (_: Exception) { 1500 }
            out.name = ni.name
            val addrs = ni.inetAddresses.toList()
                .mapNotNull { it.hostAddress }
                .filter { it.isNotBlank() }
            out.addresses = ListStringIterator(addrs)
            out.flags = 0
            out.type = 0
            out.dnsServer = ListStringIterator(emptyList())
            out.metered = false
            return out
        }
    }

    private class ListStringIterator(private val items: List<String>) : StringIterator {
        private val iter = items.iterator()
        override fun hasNext(): Boolean = iter.hasNext()
        override fun len(): Int = items.size
        override fun next(): String = iter.next()
    }

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int
    ): ConnectionOwner {
        // Бросаем Exception — иначе SIGSEGV в Go (см. ранний баг-репорт).
        throw Exception("not implemented")
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        listener ?: return
        val mgr = cm ?: return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateFromNetwork(network, listener)
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                updateFromNetwork(network, listener, caps)
            }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                updateFromNetwork(network, listener, lp = lp)
            }
            override fun onLost(network: Network) {
                Log.i(TAG, "default network lost")
            }
        }
        try {
            mgr.registerDefaultNetworkCallback(cb)
            callbackRef.set(cb)
            Log.i(TAG, "default-interface monitor started")
        } catch (e: Exception) {
            Log.w(TAG, "registerDefaultNetworkCallback failed: ${e.message}")
        }
    }

    private fun updateFromNetwork(
        network: Network,
        listener: InterfaceUpdateListener,
        caps: NetworkCapabilities? = null,
        lp: LinkProperties? = null,
    ) {
        try {
            val name = (lp ?: cm?.getLinkProperties(network))?.interfaceName
                ?: return
            val ji = try { java.net.NetworkInterface.getByName(name) } catch (_: Exception) { null }
            val index = ji?.index ?: 0
            val capsR = caps ?: cm?.getNetworkCapabilities(network)
            val isExpensive = capsR?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
            val isConstrained = false

            val di = DefaultInterface(name, index, isExpensive, isConstrained)
            val prev = currentDefault.getAndSet(di)
            if (prev != di) {
                Log.i(TAG, "default interface: $name (index=$index, metered=$isExpensive)")
                try {
                    listener.updateDefaultInterface(name, index, isExpensive, isConstrained)
                } catch (e: Exception) {
                    Log.w(TAG, "updateDefaultInterface failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateFromNetwork failed: ${e.message}")
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        val cb = callbackRef.getAndSet(null) ?: return
        try { cm?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        Log.i(TAG, "default-interface monitor stopped")
    }

    override fun sendNotification(notification: Notification?) {
        notification ?: return
        try {
            Log.i(TAG, "Notification from sing-box: ${notification.title} — ${notification.subtitle}")
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "AmSalesPlatform"
    }
}
