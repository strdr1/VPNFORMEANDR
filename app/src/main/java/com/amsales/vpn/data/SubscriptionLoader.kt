package com.amsales.vpn.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * Загрузка подписок (vless://...список) и парсинг happ://-ссылок.
 *
 * Поддерживаемые форматы:
 *   - vless://...                           → один ключ
 *   - https://example.com/sub               → подписка (base64 список ИЛИ
 *                                              JSON outbounds[])
 *   - happ://add/<URL>                      → подписка с обёрткой
 *   - happ://add/<base64-encoded URL>       → то же, но закодировано
 *   - happ://crypt5/...                     → не поддерживается (требует
 *                                              реверса формата)
 *
 * Возвращает список найденных vless://-строк (может быть пустым).
 */
object SubscriptionLoader {

    /** Парсит вход — либо ключ, либо подписка/happ. Делает сетевой запрос
     *  если требуется. Возвращает список ключей. */
    fun loadOrParse(input: String): Result<List<String>> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("пустой ввод"))

        // 1) Прямой vless://
        if (trimmed.startsWith("vless://", ignoreCase = true)) {
            return if (VlessKey.parse(trimmed) != null)
                Result.success(listOf(trimmed))
            else
                Result.failure(IllegalArgumentException("неверный vless://-ключ"))
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
            "Поддерживаются: vless://, happ://add/, ссылка на подписку (https://...)"
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
                if (decoded.startsWith("vless://", ignoreCase = true)) {
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
            // Maskuем под Happ — многие сервисы фильтруют по UA.
            conn.setRequestProperty("User-Agent", "Happ/1.0")
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val raw = conn.getInputStream().readBytes()

            // Пробуем декодировать как base64 (если содержит vless://)
            var data = raw
            val maybeDecoded = try {
                Base64.decode(String(raw).trim(),
                    Base64.URL_SAFE or Base64.DEFAULT or Base64.NO_PADDING or Base64.NO_WRAP)
            } catch (_: Exception) { null }
            if (maybeDecoded != null && String(maybeDecoded).contains("vless://")) {
                data = maybeDecoded
            }
            val text = String(data, Charsets.UTF_8)
            val found = mutableListOf<String>()

            // Plain-список (по строкам)
            text.split('\n', '\r').forEach { line ->
                val s = line.trim()
                if (s.startsWith("vless://", ignoreCase = true)) found += s
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
                    "В подписке не найдено ключей vless"
                ))
            }
            Result.success(real)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
