package com.amsales.vpn.data

import android.net.Uri

/**
 * Распарсенный VLESS-URI. Хранит всё что нужно для построения sing-box-конфига.
 *
 * Поддерживаем поля:
 *   - uuid (часть user@)
 *   - host, port
 *   - type=tcp|ws|grpc (transport)
 *   - security=reality|tls|none
 *   - sni, pbk, sid, fp (REALITY)
 *   - path, host header (для WS)
 *   - tag (после #)
 */
data class VlessKey(
    val raw: String,
    val tag: String,
    val uuid: String,
    val host: String,
    val port: Int,
    val type: String,           // tcp / ws / grpc
    val security: String,       // reality / tls / none
    val sni: String,
    val fp: String,
    val pbk: String,
    val sid: String,
    val path: String,           // ws path
    val hostHeader: String,     // ws host header
) {
    companion object {
        fun parse(uri: String): VlessKey? {
            if (!uri.startsWith("vless://", ignoreCase = true)) return null
            return try {
                val u = Uri.parse(uri)
                val userInfo = u.userInfo ?: return null
                val host = u.host ?: return null
                val port = if (u.port > 0) u.port else 443
                val tag = u.fragment ?: "AM.SALES"
                val q = { k: String -> u.getQueryParameter(k) ?: "" }

                VlessKey(
                    raw = uri,
                    tag = tag,
                    uuid = userInfo,
                    host = host,
                    port = port,
                    type = q("type").ifEmpty { "tcp" },
                    security = q("security").ifEmpty { "none" },
                    sni = q("sni"),
                    fp = q("fp").ifEmpty { "chrome" },
                    pbk = q("pbk"),
                    sid = q("sid"),
                    path = q("path").ifEmpty { "/" },
                    hostHeader = q("host"),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
