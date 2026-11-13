package com.amsales.vpn.data

import android.util.Base64
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Распарсенная ссылка на прокси-сервер. Хранит всё, что нужно для построения
 * sing-box outbound.
 *
 * Поддерживаем:
 *   vless://uuid@host:port?params#name
 *   vmess://base64(json)            (и vless-подобный vmess://uuid@host:port?…)
 *   trojan://password@host:port?params#name
 *   ss://base64(method:password)@host:port#name  (и legacy ss://base64(всё))
 *   hysteria2:// | hy2://auth@host:port?sni=&obfs=&obfs-password=#name
 *   tuic://uuid:password@host:port?congestion_control=&alpn=#name
 *
 * Транспорты vless/vmess/trojan: tcp, ws, grpc, httpupgrade, http (h2).
 * xhttp/splithttp/kcp/quic sing-box не умеет — такие ключи парсятся, но
 * помечаются [unsupported] и в подключение не идут.
 */
data class ProxyKey(
    val raw: String,
    val proto: String,          // vless / vmess / trojan / shadowsocks / hysteria2 / tuic
    val tag: String,
    val host: String,
    val port: Int,
    val portRange: String = "", // hysteria2 port hopping: "20000-30000,…"
    val uuid: String = "",
    val password: String = "",
    val method: String = "",    // shadowsocks cipher
    val vmessSecurity: String = "",
    val alterId: Int = 0,
    val flow: String = "",
    val type: String = "tcp",   // tcp / ws / grpc / httpupgrade / http
    val security: String = "none", // reality / tls / none
    val sni: String = "",
    val fp: String = "",
    val pbk: String = "",
    val sid: String = "",
    val alpn: List<String> = emptyList(),
    val insecure: Boolean = false,
    val path: String = "",
    val hostHeader: String = "",
    val serviceName: String = "",
    val obfs: String = "",
    val obfsPassword: String = "",
    val congestion: String = "",
    val udpRelayMode: String = "",
    val unsupported: String = "", // непусто → sing-box такое не умеет (причина)
) {
    val isSupported: Boolean get() = unsupported.isEmpty()

    /** hysteria2/tuic ходят по UDP — TCP-пинг к ним бессмысленен. */
    val isUdp: Boolean get() = proto == "hysteria2" || proto == "tuic"

    /**
     * Выход в России (по подписи из подписки): через него заблокированное
     * всё равно не откроется, а пинг у него лучший — urltest выбрал бы
     * именно его. В «Авто» такие не берём.
     */
    val isRuExit: Boolean
        get() = tag.contains("🇷🇺") ||
            tag.contains("Росси", ignoreCase = true) ||
            tag.contains("Russia", ignoreCase = true)

    companion object {
        private val SCHEMES = listOf("vless", "vmess", "trojan", "ss", "hysteria2", "hy2", "tuic")

        /** Похожа ли строка на ссылку, которую мы умеем разбирать. */
        fun looksLikeKey(s: String): Boolean {
            val scheme = s.substringBefore("://", "").lowercase()
            return scheme in SCHEMES
        }

        fun parse(uri: String): ProxyKey? = try {
            parseOrThrow(uri.trim())
        } catch (_: Exception) {
            null
        }

        private fun parseOrThrow(t: String): ProxyKey? {
            if (t.startsWith("vmess://", ignoreCase = true)) {
                parseVmessJson(t)?.let { return it }
                // Не base64-JSON → формат как у vless (uuid@host:port?...).
            }
            val p = (if (t.startsWith("ss://", ignoreCase = true)) {
                // Legacy: всё «method:password@host:port» закодировано в base64.
                var body = t.substring(5)
                var tail = ""
                val cut = body.indexOfFirst { it == '?' || it == '#' }
                if (cut >= 0) { tail = body.substring(cut); body = body.substring(0, cut) }
                body = body.trimEnd('/')
                if (!body.contains('@')) body = String(decodeB64(body), Charsets.UTF_8)
                splitUri("ss://$body$tail")
            } else {
                splitUri(t)
            }) ?: return null

            val q = p.query
            val name = p.fragment
            val port = portOf(p.port)

            val key = when (p.scheme) {
                "vless", "vmess" -> {
                    val s = stream(q, defaultSecurity = "none")
                    ProxyKey(
                        raw = t, proto = p.scheme, tag = name, host = p.host, port = port,
                        uuid = p.user,
                        vmessSecurity = if (p.scheme == "vmess") q["encryption"].orEmpty() else "",
                        flow = q["flow"].orEmpty(),
                        type = s.type, security = s.security, sni = s.sni, fp = s.fp,
                        pbk = q["pbk"].orEmpty(), sid = q["sid"].orEmpty(),
                        alpn = s.alpn, insecure = s.insecure, path = s.path,
                        hostHeader = q["host"].orEmpty(), serviceName = q["serviceName"].orEmpty(),
                        unsupported = s.unsupported,
                    ).takeIf { it.uuid.isNotEmpty() }
                }
                "trojan" -> {
                    val s = stream(q, defaultSecurity = "tls")
                    ProxyKey(
                        raw = t, proto = "trojan", tag = name, host = p.host, port = port,
                        password = p.user,
                        type = s.type, security = s.security, sni = s.sni, fp = s.fp,
                        pbk = q["pbk"].orEmpty(), sid = q["sid"].orEmpty(),
                        alpn = s.alpn, insecure = s.insecure, path = s.path,
                        hostHeader = q["host"].orEmpty(), serviceName = q["serviceName"].orEmpty(),
                        unsupported = s.unsupported,
                    ).takeIf { it.password.isNotEmpty() }
                }
                "ss" -> {
                    var cred = p.user
                    if (!cred.contains(':')) cred = String(decodeB64(cred), Charsets.UTF_8)
                    ProxyKey(
                        raw = t, proto = "shadowsocks", tag = name, host = p.host, port = port,
                        method = cred.substringBefore(':'),
                        password = cred.substringAfter(':', ""),
                        unsupported = if (q["plugin"].isNullOrEmpty()) "" else "ss-plugin",
                    ).takeIf { it.method.isNotEmpty() && it.password.isNotEmpty() }
                }
                "hysteria2", "hy2" -> {
                    // Port hopping: "443,20000-30000" / mport=20000-30000.
                    var ports = p.port
                    q["mport"]?.takeIf { it.isNotEmpty() }?.let { ports += ",$it" }
                    val ranges = ports.split(',').filter { it.contains('-') }
                    ProxyKey(
                        raw = t, proto = "hysteria2", tag = name, host = p.host, port = port,
                        portRange = ranges.joinToString(","),
                        password = p.user,
                        sni = q["sni"].orEmpty(),
                        insecure = isTrue(q["insecure"]) || isTrue(q["allowInsecure"]),
                        obfs = q["obfs"].orEmpty(), obfsPassword = q["obfs-password"].orEmpty(),
                        alpn = splitList(q["alpn"]),
                    ).takeIf { it.password.isNotEmpty() }
                }
                "tuic" -> ProxyKey(
                    raw = t, proto = "tuic", tag = name, host = p.host, port = port,
                    uuid = p.user.substringBefore(':'),
                    password = p.user.substringAfter(':', ""),
                    sni = q["sni"].orEmpty(),
                    insecure = isTrue(q["allow_insecure"]) || isTrue(q["insecure"]),
                    congestion = q["congestion_control"].orEmpty(),
                    udpRelayMode = q["udp_relay_mode"].orEmpty(),
                    alpn = splitList(q["alpn"]).ifEmpty { listOf("h3") },
                ).takeIf { it.uuid.isNotEmpty() }
                else -> null  // socks/http/wireguard и прочее — не поддерживаем
            } ?: return null

            return if (key.tag.isEmpty()) key.copy(tag = key.host) else key
        }

        private fun parseVmessJson(t: String): ProxyKey? {
            val js = String(decodeB64(t.substring(8).substringBefore('#')), Charsets.UTF_8)
            val o = try { JSONObject(js) } catch (_: Exception) { return null }
            fun str(k: String) = o.opt(k)?.toString()?.trim().orEmpty()
            val net = normalizeType(str("net").lowercase())
            val path = str("path")
            var unsupported = transportProblem(net)
            if (str("type") == "http" && unsupported.isEmpty()) unsupported = "tcp+http"
            val key = ProxyKey(
                raw = t, proto = "vmess", tag = str("ps"), host = str("add"),
                port = portOf(str("port")),
                uuid = str("id"), alterId = str("aid").toIntOrNull() ?: 0,
                vmessSecurity = str("scy"),
                type = net, security = str("tls").lowercase().ifEmpty { "none" },
                sni = str("sni"), fp = str("fp"),
                alpn = splitList(str("alpn")),
                path = path, hostHeader = str("host"),
                serviceName = if (net == "grpc") path else "",
                unsupported = unsupported,
            )
            if (key.uuid.isEmpty() || key.host.isEmpty()) return null
            return if (key.tag.isEmpty()) key.copy(tag = key.host) else key
        }

        // ── Транспорт/TLS из query (vless/vmess/trojan) ─────────────────

        private class Stream(
            val type: String, val security: String, val sni: String, val fp: String,
            val alpn: List<String>, val insecure: Boolean, val path: String,
            val unsupported: String,
        )

        private fun stream(q: Map<String, String>, defaultSecurity: String): Stream {
            val type = normalizeType(q["type"].orEmpty().lowercase())
            var unsupported = transportProblem(type)
            if (q["headerType"] == "http" && unsupported.isEmpty()) unsupported = "tcp+http"
            return Stream(
                type = type,
                security = q["security"].orEmpty().lowercase().ifEmpty { defaultSecurity },
                sni = q["sni"].orEmpty().ifEmpty { q["peer"].orEmpty() },
                fp = q["fp"].orEmpty(),
                alpn = splitList(q["alpn"]),
                insecure = isTrue(q["allowInsecure"]) || isTrue(q["insecure"]),
                path = q["path"].orEmpty(),
                unsupported = unsupported,
            )
        }

        private fun normalizeType(t: String) = when (t) {
            "", "raw" -> "tcp"
            "h2" -> "http"
            else -> t
        }

        private val SUPPORTED_TRANSPORTS = setOf("tcp", "ws", "grpc", "httpupgrade", "http")

        /** "" если транспорт поддерживается, иначе его имя (xhttp, kcp…). */
        private fun transportProblem(type: String) =
            if (type in SUPPORTED_TRANSPORTS) "" else type

        // ── Разбор scheme://user@host:port/?query#name ──────────────────
        // Вручную: android.net.Uri ломается на диапазонах портов hysteria2
        // ("443,20000-30000") и на base64 с '/'.

        private class Parts(
            val scheme: String, val user: String, val host: String, val port: String,
            val query: Map<String, String>, val fragment: String,
        )

        private fun splitUri(uri: String): Parts? {
            val se = uri.indexOf("://")
            if (se <= 0) return null
            val scheme = uri.substring(0, se).lowercase()
            var rest = uri.substring(se + 3)
            var fragment = ""
            rest.indexOf('#').takeIf { it >= 0 }?.let {
                fragment = urlDecode(rest.substring(it + 1)).trim()
                rest = rest.substring(0, it)
            }
            var queryStr = ""
            rest.indexOf('?').takeIf { it >= 0 }?.let {
                queryStr = rest.substring(it + 1)
                rest = rest.substring(0, it)
            }
            var user = ""
            var hostport = rest
            rest.lastIndexOf('@').takeIf { it >= 0 }?.let {
                user = urlDecode(rest.substring(0, it))
                hostport = rest.substring(it + 1)
            }
            hostport = hostport.substringBefore('/')
            val host: String
            var port = ""
            if (hostport.startsWith("[")) {
                val rb = hostport.indexOf(']')
                if (rb < 0) return null
                host = hostport.substring(1, rb)
                val tail = hostport.substring(rb + 1)
                if (tail.startsWith(":")) port = tail.substring(1)
            } else {
                val c = hostport.indexOf(':')
                if (c >= 0) { host = hostport.substring(0, c); port = hostport.substring(c + 1) }
                else host = hostport
            }
            if (host.isEmpty()) return null
            val query = LinkedHashMap<String, String>()
            for (pair in queryStr.split('&')) {
                if (pair.isEmpty()) continue
                val k = urlDecode(pair.substringBefore('='))
                val v = urlDecode(pair.substringAfter('=', "")).trim()
                query.putIfAbsent(k, v)
            }
            return Parts(scheme, user, host, port, query, fragment)
        }

        // URLDecoder превращает '+' в пробел — в ключах '+' бывает в base64,
        // поэтому экранируем его заранее.
        private fun urlDecode(s: String): String = try {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) { s }

        private fun portOf(p: String): Int {
            val v = p.split(',', '-').firstOrNull()?.trim()?.toIntOrNull()
            return if (v != null && v in 1..65535) v else 443
        }

        private fun isTrue(v: String?) = v == "1" || v.equals("true", ignoreCase = true)

        private fun splitList(v: String?) =
            v.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

        /** base64 из подписок/ключей: бывает url-safe, без паддинга, с переносами. */
        fun decodeB64(s: String): ByteArray {
            val sb = StringBuilder(s.filterNot { it.isWhitespace() }
                .replace('-', '+').replace('_', '/'))
            while (sb.length % 4 != 0) sb.append('=')
            return try { Base64.decode(sb.toString(), Base64.DEFAULT) } catch (_: Exception) { ByteArray(0) }
        }
    }
}
