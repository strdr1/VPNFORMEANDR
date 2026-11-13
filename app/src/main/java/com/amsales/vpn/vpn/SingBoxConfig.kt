package com.amsales.vpn.vpn

import com.amsales.vpn.data.ProxyKey
import com.amsales.vpn.data.Repository
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
 *   - До VPN-серверов (host из ключей) — direct
 *   - bypass-сайты пользователя — direct
 *   - .ru-домены — direct (если routeRuDirect=true)
 *   - всё остальное — proxy (один сервер или urltest-группа «Авто»)
 */
object SingBoxConfig {

    /** clash API: приложение делает через него delay-тест и узнаёт, какой
     *  сервер выбрал urltest. Только на 127.0.0.1. */
    const val CLASH_API_PORT = 9097

    /** URL для проверки связи (204 без тела) — и для urltest, и для нас. */
    const val TEST_URL = "https://www.gstatic.com/generate_204"

    /** Тег outbound'а сервера: один сервер — "proxy", в группе — "s<i>". */
    fun serverTag(index: Int, count: Int) = if (count == 1) "proxy" else "s$index"

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
    fun buildDpiOnly(settings: Repository, tunMtu: Int, logPath: String = ""): String {
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

        // Outbounds — НЕТ `block`! В sing-box 1.13+ block outbound deprecated,
        // блокировка делается через action: "reject" в route.rules.
        val direct = JSONObject().apply {
            put("type", "direct"); put("tag", "direct")
        }
        val dpiBypass = if (settings.dpiServices.isNotEmpty()) {
            JSONObject().apply {
                put("type", "socks"); put("tag", "dpi-bypass")
                put("server", "127.0.0.1")
                put("server_port", AmSalesVpnService.DPI_PORT)
                put("version", "5")
                // ВАЖНО: TCP only — наш DpiSocksServer не умеет UDP.
                // YouTube/QUIC (UDP 443) уйдёт на direct fallback.
                put("network", "tcp")
            }
        } else null

        // Route rules
        val rules = JSONArray()
        rules.put(JSONObject().put("action", "sniff"))
        rules.put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
        rules.put(JSONObject()
            .put("ip_cidr", JSONArray().put("127.0.0.0/8"))
            .put("action", "route").put("outbound", "direct"))
        // (наш UID исключён из TUN через addDisallowedApplication,
        //  правило package_name не нужно)

        // Блокировка рекламы (Google ads) — action: reject (sing-box 1.13+)
        val adJson = JSONArray()
        AD_DOMAINS.forEach { adJson.put(it) }
        rules.put(JSONObject()
            .put("domain_suffix", adJson)
            .put("action", "reject"))

        // DPI-обход
        if (dpiBypass != null) {
            val dpiDomains = JSONArray()
            for (id in settings.dpiServices) {
                val svc = com.amsales.vpn.data.DpiServices.byId(id) ?: continue
                for (d in svc.domains) dpiDomains.put(d)
            }
            if (dpiDomains.length() > 0) {
                // QUIC (UDP 443) → пускаем direct, но БЕЗ фрагментации.
                // Провайдер РКН SNI-блок видит QUIC ClientHello, рвёт.
                // YouTube видит "QUIC fails" → fallback на TCP → наш SOCKS.
                // Вариант с reject silent-drop'ом — YouTube ретраил бесконечно.
                rules.put(JSONObject()
                    .put("domain_suffix", dpiDomains)
                    .put("network", "udp")
                    .put("port", 443)
                    .put("action", "route").put("outbound", "direct"))
                // TCP — в наш DPI-bypass SOCKS
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

        val obs = JSONArray().put(direct)
        if (dpiBypass != null) obs.put(dpiBypass)

        val root = JSONObject().apply {
            val logJson = JSONObject().put("level", "debug").put("timestamp", true)
            if (logPath.isNotEmpty()) logJson.put("output", logPath)
            put("log", logJson)
            put("dns", dns)
            put("inbounds", JSONArray().put(tun))
            put("outbounds", obs)
            put("route", route)
        }
        return root.toString(2)
    }

    /**
     * @param enableDpiBypass — направлять ли Zapret-сервисы через локальный
     *   SOCKS+TLS-фраг (true для hybrid режима). false = чистый VPN, всё
     *   через VLESS-туннель, никаких локальных DPI-обходов.
     */
    /** Outbound sing-box для одного сервера. */
    fun outbound(k: ProxyKey, tag: String): JSONObject {
        val ob = JSONObject()
            .put("type", k.proto)
            .put("tag", tag)
            .put("server", k.host)
            .put("server_port", k.port)

        fun alpnArr() = JSONArray().also { a -> k.alpn.forEach { a.put(it) } }

        // TLS для vless/vmess/trojan (security=none → без TLS).
        fun streamTls() {
            if (k.security.isEmpty() || k.security == "none") return
            val tls = JSONObject().put("enabled", true)
                // server_name: sni, иначе host-заголовок (ws через CF — домен CF).
                .put("server_name", k.sni.ifEmpty { k.hostHeader.ifEmpty { k.host } })
            if (k.insecure) tls.put("insecure", true)
            if (k.alpn.isNotEmpty()) tls.put("alpn", alpnArr())
            // REALITY без uTLS не работает; для обычного TLS — только если задан fp.
            val fp = k.fp.ifEmpty { if (k.security == "reality") "chrome" else "" }
            if (fp.isNotEmpty()) {
                tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fp))
            }
            if (k.security == "reality") {
                tls.put("reality", JSONObject()
                    .put("enabled", true)
                    .put("public_key", k.pbk)
                    .put("short_id", k.sid))
            }
            ob.put("tls", tls)
        }

        fun transport() {
            val tr = when (k.type) {
                "grpc" -> JSONObject().put("type", "grpc").apply {
                    if (k.serviceName.isNotEmpty()) put("service_name", k.serviceName)
                }
                "ws" -> JSONObject().put("type", "ws").apply {
                    var path = k.path
                    // "/path?ed=2048" — early data в Sec-WebSocket-Protocol (как в xray).
                    Regex("""[?&]ed=(\d+)""").find(path)?.let { m ->
                        put("max_early_data", m.groupValues[1].toInt())
                        put("early_data_header_name", "Sec-WebSocket-Protocol")
                        path = path.removeRange(m.range)
                    }
                    put("path", path.ifEmpty { "/" })
                    // Host-заголовок (важно для Cloudflare-роутинга).
                    put("headers", JSONObject().put("Host", k.hostHeader.ifEmpty { k.host }))
                }
                "httpupgrade" -> JSONObject().put("type", "httpupgrade").apply {
                    if (k.path.isNotEmpty()) put("path", k.path)
                    if (k.hostHeader.isNotEmpty()) put("host", k.hostHeader)
                }
                "http" -> JSONObject().put("type", "http").apply {
                    if (k.path.isNotEmpty()) put("path", k.path)
                    if (k.hostHeader.isNotEmpty()) put("host", JSONArray().put(k.hostHeader))
                }
                else -> return   // tcp — без transport
            }
            ob.put("transport", tr)
        }

        when (k.proto) {
            "vless" -> {
                ob.put("uuid", k.uuid)
                // Vision работает только поверх голого TCP.
                if (k.flow.isNotEmpty() && k.type == "tcp") ob.put("flow", k.flow)
                ob.put("packet_encoding", "xudp")
                streamTls(); transport()
            }
            "vmess" -> {
                ob.put("uuid", k.uuid)
                ob.put("security", k.vmessSecurity.takeIf { it.isNotEmpty() && it != "none" } ?: "auto")
                ob.put("alter_id", k.alterId)
                ob.put("packet_encoding", "xudp")
                streamTls(); transport()
            }
            "trojan" -> {
                ob.put("password", k.password)
                streamTls(); transport()
            }
            "shadowsocks" -> {
                ob.put("method", k.method)
                ob.put("password", k.password)
            }
            "hysteria2" -> {
                ob.put("password", k.password)
                if (k.portRange.isNotEmpty()) {
                    val ports = JSONArray()
                    k.portRange.split(',').forEach { ports.put(it.replace('-', ':')) }
                    ob.put("server_ports", ports)
                }
                if (k.obfs.isNotEmpty()) {
                    ob.put("obfs", JSONObject().put("type", k.obfs).put("password", k.obfsPassword))
                }
                val tls = JSONObject().put("enabled", true).put("server_name", k.sni.ifEmpty { k.host })
                if (k.insecure) tls.put("insecure", true)
                if (k.alpn.isNotEmpty()) tls.put("alpn", alpnArr())
                ob.put("tls", tls)
            }
            "tuic" -> {
                ob.put("uuid", k.uuid)
                ob.put("password", k.password)
                if (k.congestion.isNotEmpty()) ob.put("congestion_control", k.congestion)
                if (k.udpRelayMode.isNotEmpty()) ob.put("udp_relay_mode", k.udpRelayMode)
                val tls = JSONObject().put("enabled", true)
                    .put("server_name", k.sni.ifEmpty { k.host })
                    .put("alpn", alpnArr())
                if (k.insecure) tls.put("insecure", true)
                ob.put("tls", tls)
            }
        }
        return ob
    }

    /**
     * @param keys — один сервер → outbound "proxy"; несколько → s0..sN и
     *   urltest-группа "proxy" («Авто»: sing-box держит самый быстрый живой
     *   сервер и сам переключается, если он умер).
     */
    fun build(
        keys: List<ProxyKey>, settings: Repository, tunMtu: Int, logPath: String = "",
        enableDpiBypass: Boolean = false, clashApi: Boolean = true,
    ): String {
        require(keys.isNotEmpty()) { "no servers" }
        val serverOutbounds = keys.mapIndexed { i, k -> outbound(k, serverTag(i, keys.size)) }
        val urlTest = if (keys.size > 1) {
            val tags = JSONArray()
            keys.indices.forEach { tags.put(serverTag(it, keys.size)) }
            JSONObject()
                .put("type", "urltest")
                .put("tag", "proxy")
                .put("outbounds", tags)
                .put("url", TEST_URL)
                .put("interval", "3m")
                .put("tolerance", 80)
                .put("idle_timeout", "30m")
        } else null

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

        // Inbound VPN-bridge — SOCKS5 на 127.0.0.1:9444. Наш DpiSocksServer
        // использует его как fallback-путь в Happy Eyeballs race: открывает
        // direct-сокет к Google (с фрагментацией) И параллельно socket к 9444
        // (через VLESS-туннель). Первый пробивший выигрывает.
        // Доступен только в VPN/Hybrid режимах (где есть VLESS-outbound).
        val vpnBridge = JSONObject().apply {
            put("type", "mixed")
            put("tag", "vpn-bridge-in")
            put("listen", "127.0.0.1")
            put("listen_port", AmSalesVpnService.VPN_BRIDGE_PORT)
            // КРИТИЧНО: sniff=false — иначе sing-box пытается читать TLS из
            // сокета чтобы понять SNI, что блокирует наш byte-pipe.
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
                // DoH (HTTPS) вместо DoT (TLS) — HTTP-запросы легче проходят
                // через медленные/нестабильные туннели (короткий handshake,
                // pipelining). DoT в нашем gRPC-VLESS падал с
                // "context canceled" — Cloudflare не успевал ответить.
                .put(JSONObject()
                    .put("tag", "remote")
                    .put("type", "https")
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
            if (enableDpiBypass) {
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
        // VPN-bridge inbound: всё что туда пришло идёт в VLESS-proxy.
        // Это путь fallback для Happy Eyeballs в DpiSocksServer.
        rules.put(JSONObject()
            .put("inbound", JSONArray().put("vpn-bridge-in"))
            .put("action", "route")
            .put("outbound", "proxy"))
        // localhost (наш DPI-прокси) — direct, чтобы не зациклиться через VPN
        rules.put(JSONObject()
            .put("ip_cidr", JSONArray().put("127.0.0.0/8"))
            .put("action", "route")
            .put("outbound", "direct"))
        // (package_name=com.amsales.vpn rule убран — мы добавили
        // addDisallowedApplication в openTun, наш UID не идёт в TUN)
        // Блокировка рекламы YouTube/Google — action: reject (sing-box 1.13+)
        val adJsonMain = JSONArray()
        AD_DOMAINS.forEach { adJsonMain.put(it) }
        rules.put(JSONObject()
            .put("domain_suffix", adJsonMain)
            .put("action", "reject"))
        // Exclude самих VPN-серверов. host может быть IP (2.26...) или
        // доменом (CF-Worker). ip_cidr принимает только IP — для доменов domain.
        val serverIps = JSONArray()
        val serverDomains = JSONArray()
        for (k in keys.map { it.host }.distinct()) {
            when {
                k.matches(Regex("""^[0-9.]+$""")) -> serverIps.put("$k/32")
                k.contains(':') -> serverIps.put("$k/128")
                else -> serverDomains.put(k)
            }
        }
        if (serverIps.length() > 0) {
            rules.put(JSONObject()
                .put("ip_cidr", serverIps)
                .put("action", "route")
                .put("outbound", "direct"))
        }
        if (serverDomains.length() > 0) {
            rules.put(JSONObject()
                .put("domain", serverDomains)
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
        // enableDpiBypass=true только в hybrid-режиме.
        val dpiDomains = JSONArray()
        if (enableDpiBypass) {
            for (id in settings.dpiServices) {
                val svc = com.amsales.vpn.data.DpiServices.byId(id) ?: continue
                for (d in svc.domains) dpiDomains.put(d)
            }
        }
        if (dpiDomains.length() > 0) {
            // QUIC (UDP 443) → reject c method:default — ICMP unreachable,
            // quic-go сразу падает на TCP. Без method = silent drop = бесконечный
            // ретрай QUIC и TCP fallback не срабатывает.
            rules.put(JSONObject()
                .put("domain_suffix", dpiDomains)
                .put("network", "udp")
                .put("action", "reject")
                .put("method", "default"))
            // TCP → в DPI-bypass SOCKS
            rules.put(JSONObject()
                .put("domain_suffix", dpiDomains)
                .put("action", "route")
                .put("outbound", "dpi-bypass"))
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
        // НЕТ `block` outbound — в sing-box 1.13+ это deprecated.
        // Блокировка делается через action: "reject" в route-правиле.

        // SOCKS5 outbound на наш локальный DPI-фрагментирующий прокси.
        // Включаем только если есть выбранные DPI-сервисы и включён dpi-bypass (hybrid режим).
        val dpiBypass = if (enableDpiBypass && settings.dpiServices.isNotEmpty()) {
            JSONObject().apply {
                put("type", "socks")
                put("tag", "dpi-bypass")
                put("server", "127.0.0.1")
                put("server_port", AmSalesVpnService.DPI_PORT)
                put("version", "5")
                // TCP only — UDP/QUIC YouTube'а пойдёт через VPN-proxy
                put("network", "tcp")
            }
        } else null

        val root = JSONObject().apply {
            val logJson = JSONObject().put("level", "debug").put("timestamp", true)
            if (logPath.isNotEmpty()) logJson.put("output", logPath)
            put("log", logJson)
            put("dns", dns)
            put("inbounds", JSONArray().put(tun).put(vpnBridge))
            val obs = JSONArray()
            serverOutbounds.forEach { obs.put(it) }
            if (urlTest != null) obs.put(urlTest)
            obs.put(direct)
            if (dpiBypass != null) obs.put(dpiBypass)
            put("outbounds", obs)
            put("route", route)
            // clash API — delay-тест сквозь туннель и выбор urltest.
            if (clashApi) {
                put("experimental", JSONObject().put("clash_api", JSONObject()
                    .put("external_controller", "127.0.0.1:$CLASH_API_PORT")))
            }
        }

        return root.toString(2)
    }
}
