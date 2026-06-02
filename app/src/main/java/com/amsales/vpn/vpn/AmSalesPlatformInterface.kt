package com.amsales.vpn.vpn

import android.os.ParcelFileDescriptor
import android.util.Log
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState

/**
 * Реализация libbox.PlatformInterface — мост между Java/Kotlin и Go-кодом
 * sing-box. Самый важный метод — openTun(options): здесь мы поднимаем
 * VpnService.Builder с параметрами от sing-box, делаем establish() и
 * возвращаем целочисленный fd, который Go-сторона использует как TUN.
 *
 * Минимально-достаточная реализация для VLESS+REALITY. Многие методы
 * возвращают пустые значения / null — sing-box тогда использует свой
 * собственный механизм (например, sniff network interfaces изнутри Go).
 */
class AmSalesPlatformInterface(
    private val service: AmSalesVpnService,
    private val blacklistApps: Set<String>,
) : PlatformInterface {

    @Volatile var tunFd: ParcelFileDescriptor? = null
        private set

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
        // IPv6 addresses (если есть)
        val v6iter = options.inet6Address
        while (v6iter != null && v6iter.hasNext()) {
            val p = v6iter.next() ?: continue
            try { builder.addAddress(p.address(), p.prefix()) }
            catch (_: Exception) {}
        }

        // Routes: если включён autoRoute — sing-box сам передаст 0.0.0.0/0
        // через RouteAddress; иначе используем дефолт.
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

        // DNS
        try {
            val dns = options.dnsServerAddress
            if (dns != null && dns.value.isNotBlank()) {
                builder.addDnsServer(dns.value)
            } else {
                builder.addDnsServer("1.1.1.1")
                builder.addDnsServer("77.88.8.8")
            }
        } catch (_: Exception) {
            builder.addDnsServer("1.1.1.1")
        }

        // Split tunneling — приложения мимо VPN (наш blacklist)
        for (pkg in blacklistApps) {
            try { builder.addDisallowedApplication(pkg) }
            catch (e: Exception) { Log.w(TAG, "skip $pkg: ${e.message}") }
        }
        // Сам себя — мимо туннеля (иначе зацикливается)
        try { builder.addDisallowedApplication(service.packageName) } catch (_: Exception) {}

        // sing-box может передать включаемые/исключаемые приложения
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
        Log.i(TAG, "TUN установлен, fd=${pfd.fd}")
        return pfd.fd
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        // Защита сокета от попадания обратно в TUN.
        try {
            service.protect(fd)
        } catch (e: Exception) {
            Log.w(TAG, "protect($fd) failed: ${e.message}")
        }
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    // ВАЖНО: useProcFS=true заставляет sing-box искать UID через /proc сам.
    // Если оставить false — он зовёт findConnectionOwner на каждом TCP-пакете,
    // и если мы вернём null или невалидный ConnectionOwner — Go-runtime
    // делает result.UserId на nil pointer и весь sing-box падает SIGSEGV
    // (service.go:218 в sing-box v1.13.12 — нет nil-check).
    override fun useProcFS(): Boolean = true

    override fun includeAllNetworks(): Boolean = false

    override fun underNetworkExtension(): Boolean = false

    override fun clearDNSCache() {
        // No-op — Android сам управляет DNS-кэшем
    }

    override fun readWIFIState(): WIFIState? = null

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun systemCertificates(): StringIterator? = null

    override fun getInterfaces(): NetworkInterfaceIterator? = null

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int
    ): ConnectionOwner {
        // НИКОГДА не возвращать null — Go-сторона sing-box v1.13.12
        // не проверяет на nil и разыменовывает result.UserId → SIGSEGV.
        // Бросаем Exception — Go получит err и продолжит без process-info.
        throw Exception("not implemented")
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        // No-op — sing-box справится со своим internal monitor
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        // No-op
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
