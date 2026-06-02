package com.amsales.vpn.data

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Единая точка входа в стор. Все экраны Compose получают её через
 * LocalRepository, ViewModel'и тоже. Внутри SharedPreferences + потом
 * добавим DataStore если понадобится reactive.
 *
 * Содержит:
 *  - profiles (список VLESS-ключей с метаданными)
 *  - currentProfileIndex
 *  - settings (флаги: routeRuDirect, useZapret, …)
 *  - blacklistApps (split tunneling)
 */
class Repository(private val context: Context) {

    private val store = Settings(context)

    // ── Профили ─────────────────────────────────────────────────────────

    data class Profile(
        val raw: String,
        val key: VlessKey?    // распарсенный, null = битый ключ
    ) {
        val name: String get() = key?.tag ?: "?"
        val host: String get() = key?.host ?: "?"
    }

    fun profiles(): List<Profile> = store.keys.map { Profile(it, VlessKey.parse(it)) }

    fun currentIndex(): Int = store.currentKeyIndex
    fun setCurrentIndex(i: Int) { store.currentKeyIndex = i }
    fun current(): Profile? = profiles().getOrNull(currentIndex())

    fun addKey(uri: String): Boolean {
        if (VlessKey.parse(uri) == null) return false
        store.keys = store.keys + uri
        return true
    }

    /** Добавляет сразу пачку — для импорта подписки. Дубли пропускаются. */
    fun addKeys(uris: List<String>): Int {
        val current = store.keys.toMutableList()
        var added = 0
        for (uri in uris) {
            if (VlessKey.parse(uri) == null) continue
            if (uri in current) continue
            current += uri
            added++
        }
        store.keys = current
        return added
    }

    /** Включён ли «авто-подключение при старте Android» */
    var autoConnectOnBoot: Boolean
        get() = context.prefs().getBoolean("auto_connect_on_boot", false)
        set(v) { context.prefs().edit().putBoolean("auto_connect_on_boot", v).apply() }

    fun removeAt(index: Int) {
        val list = store.keys.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        store.keys = list
        if (store.currentKeyIndex >= list.size) {
            store.currentKeyIndex = (list.size - 1).coerceAtLeast(0)
        }
    }

    fun replaceAt(index: Int, uri: String): Boolean {
        if (VlessKey.parse(uri) == null) return false
        val list = store.keys.toMutableList()
        if (index !in list.indices) return false
        list[index] = uri
        store.keys = list
        return true
    }

    fun ensureDefaults() = store.ensureDefaults()

    // ── Настройки (флаги как в десктопе) ───────────────────────────────

    var routeRuDirect: Boolean
        get() = context.prefs().getBoolean("route_ru_direct", true)
        set(v) { context.prefs().edit().putBoolean("route_ru_direct", v).apply() }

    var useZapret: Boolean
        get() = context.prefs().getBoolean("use_zapret", false)
        set(v) { context.prefs().edit().putBoolean("use_zapret", v).apply() }

    var tgProxyLink: String
        get() = context.prefs().getString("tg_proxy_link", "") ?: ""
        set(v) { context.prefs().edit().putString("tg_proxy_link", v).apply() }

    var bypassSites: List<String>
        get() = context.prefs().getString("bypass_sites", "")
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        set(v) { context.prefs().edit().putString("bypass_sites", v.joinToString(",")).apply() }

    // ── TG-прокси (локальный MTProto WS-bridge) ─────────────────────────

    /** 32-символьный hex-секрет MTProto. Генерится при первом запуске движка. */
    var tgSecret: String
        get() = context.prefs().getString("tg_secret", "") ?: ""
        set(v) { context.prefs().edit().putString("tg_secret", v).apply() }

    /** Cloudflare Worker для туннелирования (опционально). */
    var tgWorkerDomain: String
        get() = context.prefs().getString("tg_worker_domain",
            "shiny-hill-d2ef.danecc5678.workers.dev") ?: ""
        set(v) { context.prefs().edit().putString("tg_worker_domain", v).apply() }

    /** Fake TLS маскировочный домен (опционально). */
    var tgFakeTlsDomain: String
        get() = context.prefs().getString("tg_fake_tls", "") ?: ""
        set(v) { context.prefs().edit().putString("tg_fake_tls", v).apply() }

    // ── Split tunneling ────────────────────────────────────────────────

    var blacklistApps: Set<String>
        get() = store.blacklistApps
        set(v) { store.blacklistApps = v }
}

private fun Context.prefs() = getSharedPreferences("amsales_vpn", Context.MODE_PRIVATE)

val LocalRepository = staticCompositionLocalOf<Repository> {
    error("Repository not provided")
}
