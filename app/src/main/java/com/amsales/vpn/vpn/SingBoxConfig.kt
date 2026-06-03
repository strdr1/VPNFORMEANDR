package com.amsales.vpn.vpn

import com.amsales.vpn.data.Repository
import com.amsales.vpn.data.VlessKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Генерация sing-box-конфига для libbox-embedded режима.
 *
 * libbox запускает sing-box внутри нашего процесса. TUN-устройство при
 * этом поднимает наш PlatformInterface.openTun() — поэтому НЕ передаём
 * file_descriptor в JSON и обязательно ставим auto_route=true, чтобы
 * sing-box вызвал openTun платформенного интерфейса.
 *
 * Маршрутизация:
 *   - До VLESS-сервера (host из ключа) — direct
 *   - bypass-сайты пользователя — direct
 *   - .ru-домены — direct (если routeRuDirect=true)
 *   - всё остальное — proxy (vless)
 */
object SingBoxConfig {

    /** YouTube/Google ads-домены — блокируем reject'ом в обоих режимах. */
    private val AD_DOMAINS = listOf(
        "googleads.g.doubleclick.net",
        "pagead2.googlesyndication.com",
        "pagead.googlesyndication.com",
        "googlesyndication.com",
        "doubleclick.net",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "static.doubleclick.net",
        "ads.youtube.com",
        // Внутренний YouTube endpoint для рекламы
        "youtubei.googleapis.com/youtube/v1/log_event",
    )

    /**
     * DPI-only режим: VPN-туннель БЕЗ VLESS-прокси.
     * Весь трафик идёт direct, но DPI-сервисы перехватываются и проходят
     * через локальный TLS-фрагментирующий SOCKS5. Рекламные домены
     * блокируются.
     */
    fun buildDpiOnly(settings: Repository, tunMtu: Int): String {
        // Inbound TUN
        val tun = JSONObject().apply {
            put("type", "tun")
            put("tag", "tun-in")
            put("address", JSONArray().put("${AmSalesVpnService.TUN_ADDRESS}/30"))
            put("mtu", tunMtu)
            put("auto_route", true)
            put("strict_route", false)
            put("stack", "mixed")
        }

        // DNS — простой: системный UDP 8.8.8.8.
        val dns = JSONObject().apply {
            put("servers", JSONArray()
                .put(JSONObject()
                    .put("tag", "local")
                    .put("type", "udp")
                    .put("server", "8.8.8.8")))
            put("strategy", "prefer_ipv4")
            put("final", "local")
        }

        // Outbounds
        val direct = JSONObject().apply {
            put("type", "direct"); put("tag", "direct")
        }
        val block = JSONObject().apply {
            put("type", "block"); put("tag", "block")
        }
        val dpiBypass = if (settings.dpiServices.isNotEmpty()) {
            JSONObject().apply {
                put("type", "socks"); put("tag", "dpi-bypass")
                put("server", "127.0.0.1")
                put("server_port", AmSalesVpnService.DPI_PORT)
                put("version", "5")
            }
        } else null

        // Route rules
        val rules = JSONArray()
        rules.put(JSONObject().put("action", "sniff"))
        rules.put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
        rules.put(JSONObject()
            .put("ip_cidr", JSONArray().put("127.0.0.0/8"))
            .put("action", "route").put("outbound", "direct"))

        // Блокировка рекламы (Google ads)
        val adJson = JSONArray()
        AD_DOMAINS.forEach { adJson.put(it) }
        rules.put(JSONObject()
            .put("domain_suffix", adJson)
            .put("action", "route").put("outbound", "block"))

        // DPI-обход
        if (dpiBypass != null) {
            val dpiDomains = JSONArray()
            for (id in settings.dpiServices) {
                val svc = com.amsales.vpn.data.DpiServices.byId(id) ?: continue
                for (d in svc.domains) dpiDomains.put(d)
            }
            if (dpiDomains.length() > 0) {
                rules.put(JSONObject()
                    .put("domain_suffix", dpiDomains)
                    .put("action", "route").put("outbound", "dpi-bypass"))
            }
        }

        val route = JSONObject().apply {
            put("rules", rules)
            put("final", "direct")  // ← главное отличие от полного VPN
            put("auto_detect_interface", true)
            put("override_android_vpn", false)
        }

        val obs = JSONArray().put(direct).put(block)
        if (dpiBypass != null) obs.put(dpiBypass)

        val root = JSONObject().apply {
            put("log", JSONObject()
                .put("level", "warn").put("output", "stderr").put("timestamp", true))
            put("dns", dns)
            put("inbounds", JSONArray().put(tun))
            put("outbounds", obs)
            put("route", route)
        }
        return root.toString(2)
    }

    fun build(key: VlessKey, settings: Repository, tunMtu: Int): String {

        // Outbound vless
        val vless = JSONObject().apply {
            put("type", "vless")
            put("tag", "proxy")
            put("server", key.host)
            put("server_port", key.port)
            put("uuid", key.uuid)
            put("packet_encoding", "xudp")

            if (key.security != "none" && key.security.isNotEmpty()) {
                val tls = JSONObject().apply {
                    put("enabled", true)
                    if (key.sni.isNotEmpty()) put("server_name", key.sni)
                    if (key.fp.isNotEmpty()) {
                        put("utls", JSONObject()
                            .put("enabled", true)
                            .put("fingerprint", key.fp))
                    }
                    if (key.security == "reality") {
                        val reality = JSONObject().apply {
                            put("enabled", true)
                            if (key.pbk.isNotEmpty()) put("public_key", key.pbk)
                            if (key.sid.isNotEmpty()) put("short_id", key.sid)
                        }
                        put("reality", reality)
                    }
                }
                put("tls", tls)
            }

            when (key.type) {
                "ws" -> {
                    val tr = JSONObject()
                        .put("type", "ws")
                        .put("path", key.path)
                    if (key.hostHeader.isNotEmpty()) {
                        tr.put("headers", JSONObject().put("Host", key.hostHeader))
                    }
                    put("transport", tr)
                }
                "grpc" -> {
                    put("transport", JSONObject().put("type", "grpc"))
                }
            }
        }

        // Inbound TUN — без file_descriptor (libbox вызовет openTun через PlatformInterface)
        val tun = JSONObject().apply {
            put("type", "tun")
            put("tag", "tun-in")
            put("address", JSONArray().put("${AmSalesVpnService.TUN_ADDRESS}/30"))
            put("mtu", tunMtu)
            put("auto_route", true)        // важнo: триггерит вызов platform.openTun()
            put("strict_route", false)
            // mixed = system TCP + gvisor UDP. system-стек надёжнее на
            // Android чем gvisor — он не паникует в случае race conditions.
            // gvisor крашился: github.com/sagernet/sing-tun/stack_gvisor_tcp.go:94
            put("stack", "mixed")
        }

        // DNS — два сервера:
        //  · remote: TLS DoH 1.1.1.1, идёт ЧЕРЕЗ прокси (detour=proxy)
        //  · local:  UDP 8.8.8.8, без detour — sing-box возьмёт default route
        //            (защищённый VpnService.protect() через
        //            autoDetectInterfaceControl), т.е. реально пойдёт через
        //            wlan0/rmnet, а не обратно в TUN.
        //
        // Правила:
        //   · .ru-домены → local (вне VPN)
        //   · всё остальное → remote (через VPN)
        //
        // НЕ используем "outbound: direct → server: local" — это ловушка
        // в 1.13.12: direct outbound без override-полей считается "empty",
        // и detour на него запрещён, а в DNS-правилах outbound-условие
        // вызывает аналогичную проверку.
        val dns = JSONObject().apply {
            put("servers", JSONArray()
                .put(JSONObject()
                    .put("tag", "remote")
                    .put("type", "tls")
                    .put("server", "1.1.1.1")
                    .put("detour", "proxy"))
                .put(JSONObject()
                    .put("tag", "local")
                    .put("type", "udp")
                    .put("server", "8.8.8.8")))
            val dnsRules = JSONArray()
            if (settings.routeRuDirect) {
                dnsRules.put(JSONObject()
                    .put("domain_suffix", ".ru")
                    .put("server", "local"))
            }
            val bypassForDns = settings.bypassSites
            if (bypassForDns.isNotEmpty()) {
                val dom = JSONArray()
                bypassForDns.forEach { dom.put(it) }
                dnsRules.put(JSONObject()
                    .put("domain_suffix", dom)
                    .put("server", "local"))
            }
            // DPI-сервисы должны резолвиться через local DNS — иначе IP-адрес
            // youtube.com будет cloudflare-CDN из США через 1.1.1.1, и российский
            // провайдер всё равно увидит "запрос к YouTube" и заблокирует
            // независимо от того что мы делаем с TLS.
            if (settings.useZapret) {
                val dpiDomainsForDns = JSONArray()
                for (id in settings.dpiServices) {
                    val svc = com.amsales.vpn.data.DpiServices.byId(id) ?: continue
                    for (d in svc.domains) dpiDomainsForDns.put(d)
                }
                if (dpiDomainsForDns.length() > 0) {
                    dnsRules.put(JSONObject()
                        .put("domain_suffix", dpiDomainsForDns)
                        .put("server", "local"))
                }
            }
            if (dnsRules.length() > 0) put("rules", dnsRules)
            put("strategy", "prefer_ipv4")
            put("final", "remote")
        }

        // Route rules
        val rules = JSONArray()
        rules.put(JSONObject().put("action", "sniff"))
        rules.put(JSONObject()
            .put("protocol", "dns")
            .put("action", "hijack-dns"))
        // localhost (наш DPI-прокси) — direct, чтобы не зациклиться через VPN
        rules.put(JSONObject()
            .put("ip_cidr", JSONArray().put("127.0.0.0/8"))
            .put("action", "route")
            .put("outbound", "direct"))
        // Блокировка рекламы YouTube/Google (доменный reject)
        val adJson = JSONArray()
        AD_DOMAINS.forEach { adJson.put(it) }
        rules.put(JSONObject()
            .put("domain_suffix", adJson)
            .put("action", "route")
            .put("outbound", "block"))
        // Exclude самого VPN-сервера. key.host может быть IP (45.92...)
        // или доменом (для CF-Worker'а: amsales-vpn.danecc5678.workers.dev).
        // ip_cidr принимает только IP — для домена используем domain.
        val isIp = key.host.matches(Regex("""^[0-9.]+$|^[0-9a-fA-F:]+$"""))
        if (isIp) {
            rules.put(JSONObject()
                .put("ip_cidr", JSONArray().put("${key.host}/32"))
                .put("action", "route")
                .put("outbound", "direct"))
        } else {
            rules.put(JSONObject()
                .put("domain", JSONArray().put(key.host))
                .put("action", "route")
                .put("outbound", "direct"))
        }
        val bypass = settings.bypassSites
        if (bypass.isNotEmpty()) {
            val bypassJson = JSONArray()
            bypass.forEach { bypassJson.put(it) }
            rules.put(JSONObject()
                .put("domain_suffix", bypassJson)
                .put("action", "route")
                .put("outbound", "direct"))
        }
        if (settings.routeRuDirect) {
            rules.put(JSONObject()
                .put("domain_suffix", ".ru")
                .put("action", "route")
                .put("outbound", "direct"))
        }

        // DPI-обход: выбранные пользователем сервисы (YouTube/Discord/etc)
        // идут НЕ через VPN и НЕ через direct, а через наш локальный
        // SOCKS5-прокси который фрагментирует TLS Client Hello.
        // Учитываем мастер-toggle useZapret — если выключен, список игнорируется.
        val dpiDomains = JSONArray()
        if (settings.useZapret) {
            for (id in settings.dpiServices) {
                val svc = com.amsales.vpn.data.DpiServices.byId(id) ?: continue
                for (d in svc.domains) dpiDomains.put(d)
            }
        }
        if (dpiDomains.length() > 0) {
            rules.put(JSONObject()
                .put("domain_suffix", dpiDomains)
                .put("action", "route")
                .put("outbound", "dpi-bypass"))
            // IP-сервера VPN не должны ходить через DPI-обход
            // (защита от случайного коллапса)
        }

        val route = JSONObject().apply {
            put("rules", rules)
            put("final", "proxy")
            // sing-box должен сам определить физический интерфейс (wlan0/rmnet)
            // и использовать его как upstream для своих outbound-сокетов.
            put("auto_detect_interface", true)
            // КРИТИЧНО false: когда sing-box САМ работает как Android-VPN
            // (через наш AmSalesVpnService), VPN-таблица в ядре указывает
            // на НАШ ЖЕ TUN. Если override=true — sing-box выберет VPN-таблицу
            // и трафик зациклится в TUN. false → sing-box идёт на rule.Mask=0xFFFF
            // = физический wlan0/rmnet (sing-tun/monitor_android.go).
            put("override_android_vpn", false)
        }

        val direct = JSONObject().apply {
            put("type", "direct")
            put("tag", "direct")
        }
        val block = JSONObject().apply {
            put("type", "block")
            put("tag", "block")
        }

        // SOCKS5 outbound на наш локальный DPI-фрагментирующий прокси.
        // Включаем только если есть выбранные DPI-сервисы и включён zapret.
        val dpiBypass = if (settings.useZapret && settings.dpiServices.isNotEmpty()) {
            JSONObject().apply {
                put("type", "socks")
                put("tag", "dpi-bypass")
                put("server", "127.0.0.1")
                put("server_port", AmSalesVpnService.DPI_PORT)
                put("version", "5")
            }
        } else null

        val root = JSONObject().apply {
            // info-уровень — увидим в логе попытки подключения к VLESS,
            // DNS-разрешение, ошибки REALITY-handshake.
            put("log", JSONObject()
                .put("level", "debug")
                .put("output", "stderr")
                .put("timestamp", true))
            put("dns", dns)
            put("inbounds", JSONArray().put(tun))
            val obs = JSONArray()
                .put(vless)
                .put(direct)
                .put(block)
            if (dpiBypass != null) obs.put(dpiBypass)
            put("outbounds", obs)
            put("route", route)
        }

        return root.toString(2)
    }
}
