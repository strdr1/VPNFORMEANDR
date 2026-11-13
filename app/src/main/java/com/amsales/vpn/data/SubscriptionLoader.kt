package com.amsales.vpn.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * Загрузка подписок (список ключей) и парсинг happ://-ссылок.
 *
 * Поддерживаемые форматы:
 *   - vless:// vmess:// trojan:// ss:// hysteria2:// hy2:// tuic://
 *                                           → один или несколько ключей
 *                                              (по одному на строку)
 *   - https://example.com/sub               → подписка (base64 список ИЛИ
 *                                              JSON outbounds[])
 *   - happ://add/<URL>                      → подписка с обёрткой
 *   - happ://add/<base64-encoded URL>       → то же, но закодировано
 *   - happ://crypt5/...                     → не поддерживается (требует
 *                                              реверса формата)
 *
 * Возвращает список найденных ключей (может быть пустым).
 */
object SubscriptionLoader {

    /** Парсит вход — либо ключ, либо подписка/happ. Делает сетевой запрос
     *  если требуется. Возвращает список ключей. */
    fun loadOrParse(input: String): Result<List<String>> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("пустой ввод"))

        // 1) Ключ (или несколько, через пробел/перевод строки)
        if (ProxyKey.looksLikeKey(trimmed)) {
            val keys = trimmed.split(Regex("\\s+")).filter { ProxyKey.parse(it) != null }
            return if (keys.isNotEmpty())
                Result.success(keys)
            else
                Result.failure(IllegalArgumentException("не удалось распознать ключ"))
        }

        // 2) happ://
        if (trimmed.startsWith("happ://", ignoreCase = true)) {
            return parseHapp(trimmed)
        }

        // 3) Голая HTTP-ссылка
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)) {
            return loadSubscription(trimmed)
        }

        return Result.failure(IllegalArgumentException(
            "Поддерживаются: vless, vmess, trojan, ss, hysteria2, tuic, happ://add/, ссылка на подписку (https://...)"
        ))
    }

    private fun parseHapp(uri: String): Result<List<String>> {
        val rest = uri.substring(7)   // отрезаем "happ://"
        if (rest.startsWith("add/", ignoreCase = true)) {
            val payload = rest.substring(4)
            // URL внутри
            if (payload.startsWith("http", ignoreCase = true)) {
                return loadSubscription(payload)
            }
            // base64-URL
            try {
                val decoded = Base64.decode(
                    payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                ).toString(Charsets.UTF_8).trim()
                if (decoded.startsWith("http", ignoreCase = true)) {
                    return loadSubscription(decoded)
                }
                if (ProxyKey.looksLikeKey(decoded)) {
                    return Result.success(listOf(decoded))
                }
            } catch (_: Exception) {}
            return Result.failure(IllegalArgumentException(
                "happ://-ссылка: не удалось расшифровать содержимое"
            ))
        }
        if (rest.startsWith("crypt", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException(
                "happ://crypt-ссылки пока не поддерживаются"
            ))
        }
        return Result.failure(IllegalArgumentException("незнакомый формат happ://"))
    }

    /** Скачивает подписку и парсит её во все ключи которые сможет найти. */
    private fun loadSubscription(url: String): Result<List<String>> {
        return try {
            val conn = URL(url).openConnection()
            // UA клиента v2rayN: панели (Remnawave/Marzban/3x-ui) отдают на него
            // обычный base64-список ссылок, а не JSON/YAML под конкретный клиент.
            conn.setRequestProperty("User-Agent", "v2rayN/7.14.4")
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val raw = conn.getInputStream().readBytes()

            // Подписки часто закодированы base64 целиком.
            var data = raw
            if (!String(raw).contains("://")) {
                val decoded = ProxyKey.decodeB64(String(raw).trim())
                if (String(decoded).contains("://")) data = decoded
            }
            val text = String(data, Charsets.UTF_8)
            val found = mutableListOf<String>()

            // Plain-список (по строкам)
            text.split('\n', '\r').forEach { line ->
                val s = line.trim()
                if (ProxyKey.looksLikeKey(s) && ProxyKey.parse(s) != null && s !in found) found += s
            }

            // JSON-конфиг (xray/sing-box format от Happ-style сервисов)
            if (found.isEmpty()) {
                try {
                    val obj = if (text.trim().startsWith("[")) {
                        JSONArray(text).optJSONObject(0) ?: JSONObject()
                    } else JSONObject(text)
                    val outbounds = obj.optJSONArray("outbounds") ?: JSONArray()
                    for (i in 0 until outbounds.length()) {
                        val o = outbounds.optJSONObject(i) ?: continue
                        if (o.optString("protocol") != "vless" &&
                            o.optString("type") != "vless") continue
                        val settings = o.optJSONObject("settings") ?: o
                        val vnext = settings.optJSONArray("vnext")
                        if (vnext != null && vnext.length() > 0) {
                            val node = vnext.getJSONObject(0)
                            val host = node.optString("address")
                            val port = node.optInt("port", 443)
                            val users = node.optJSONArray("users")
                            val uuid = users?.optJSONObject(0)?.optString("id") ?: ""
                            if (host.isNotEmpty() && uuid.isNotEmpty()) {
                                found += "vless://$uuid@$host:$port?type=tcp&security=none#sub"
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // Детект заглушки (все @0.0.0.0)
            val real = found.filter {
                !it.contains("@0.0.0.0:") && !it.contains("@127.0.0.1:")
            }
            if (found.isNotEmpty() && real.isEmpty()) {
                return Result.failure(IllegalArgumentException(
                    "Подписка не активна или истекла (вернулся ключ-заглушка)"
                ))
            }
            if (real.isEmpty()) {
                return Result.failure(IllegalArgumentException(
                    "В подписке не найдено ключей"
                ))
            }
            Result.success(real)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
