package com.amsales.vpn.vpn

import com.amsales.vpn.data.Repository
import com.amsales.vpn.data.VlessKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Генерация JSON-конфига для sing-box (вариант 1.13+).
 *
 * Конфиг работает в режиме TUN-inbound: sing-box получает TUN-fd от
 * VpnService через флаг -t (или конфиг с file_descriptor). В нашем случае
 * проще — sing-box создаёт TUN сам, но мы наследуем FD от VpnService.
 *
 * Заворачиваем туннелем весь трафик, маршрутизация:
 *   - До нашего VLESS-сервера (host из ключа) → direct
 *   - .ru-домены / russian geoip → direct (если routeRuDirect=true)
 *   - bypass-сайты (gosuslugi.ru etc.) → direct
 *   - всё остальное → proxy (vless)
 */
object SingBoxConfig {

    fun build(key: VlessKey, settings: Repository, tunFd: Int, tunMtu: Int): String {

        // Outbound vless
        val vless = JSONObject().apply {
            put("type", "vless")
            put("tag", "proxy")
            put("server", key.host)
            put("server_port", key.port)
            put("uuid", key.uuid)
            put("packet_encoding", "xudp")
            // tcp_fast_open ускоряет установление коннекта на мобильных сетях
            put("tcp_fast_open", true)

            // TLS + REALITY (если указано)
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

            // Transport (ws / grpc / tcp)
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
                // tcp — без явного transport
            }
        }

        // Inbound TUN
        val tun = JSONObject().apply {
            put("type", "tun")
            put("tag", "tun-in")
            put("interface_name", "amsales0")
            put("address", JSONArray().apply {
                put(AmSalesVpnService.TUN_ADDRESS + "/30")
            })
            put("mtu", tunMtu)
            // file_descriptor — наш TUN-fd от VpnService.Builder.establish()
            put("file_descriptor", tunFd)
            put("auto_route", false)   // маршруты уже настроены VpnService
            put("stack", "gvisor")     // user-mode TCP/IP стек — надёжно
        }

        // DNS
        val dns = JSONObject().apply {
            put("servers", JSONArray()
                .put(JSONObject()
                    .put("tag", "remote")
                    .put("type", "https")
                    .put("server", "1.1.1.1"))
                .put(JSONObject()
                    .put("tag", "local")
                    .put("type", "udp")
                    .put("server", "77.88.8.8")))
            put("strategy", "prefer_ipv4")
            put("final", "remote")
        }

        // Route rules
        val rules = JSONArray()
        rules.put(JSONObject().put("action", "sniff"))
        rules.put(JSONObject()
            .put("protocol", "dns")
            .put("action", "hijack-dns"))
        // Исключение для самого VPN-сервера (нельзя гонять через себя же)
        rules.put(JSONObject()
            .put("ip_cidr", JSONArray().put("${key.host}/32"))
            .put("action", "route")
            .put("outbound", "direct"))
        // Bypass-сайты пользователя
        val bypass = settings.bypassSites
        if (bypass.isNotEmpty()) {
            val bypassJson = JSONArray()
            bypass.forEach { bypassJson.put(it) }
            rules.put(JSONObject()
                .put("domain_suffix", bypassJson)
                .put("action", "route")
                .put("outbound", "direct"))
        }
        // .ru-домены напрямую
        if (settings.routeRuDirect) {
            rules.put(JSONObject()
                .put("domain_suffix", ".ru")
                .put("action", "route")
                .put("outbound", "direct"))
        }

        val route = JSONObject().apply {
            put("rules", rules)
            put("final", "proxy")
            put("auto_detect_interface", true)
            put("default_domain_resolver", JSONObject().put("server", "local"))
        }

        // direct outbound: с TLS-фрагментацией если включён zapret-режим
        val direct = JSONObject().apply {
            put("type", "direct")
            put("tag", "direct")
            if (settings.useZapret) {
                // TLS fragment — режет ClientHello на куски, DPI не успевает
                // распознать SNI и не блокирует. Аналог GoodbyeDPI/zapret.
                put("tls_fragment", true)
                put("tls_fragment_fallback_delay", "500ms")
            }
        }

        val root = JSONObject().apply {
            put("log", JSONObject().put("level", "warn"))
            put("dns", dns)
            put("inbounds", JSONArray().put(tun))
            put("outbounds", JSONArray()
                .put(vless)
                .put(direct))
            put("route", route)
        }

        return root.toString(2)
    }
}
