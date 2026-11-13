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
 *  - settings (флаги: routeRuDirect, …)
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

    /** УСТАРЕЛО: раньше был мастер-toggle "обход DPI вкл/выкл".
     *  Теперь DPI-обход определяется режимом VPN/Hybrid/Zapret (vpnMode).
     *  Геттер оставлен для обратной совместимости с возможным старым кодом
     *  и всегда возвращает true. Не использовать в новом коде. */
    @Deprecated("use vpnMode instead")
    var useZapret: Boolean
        get() = true
        set(_) { /* no-op */ }

    /** Режим работы кнопки на главном экране:
     *   "vpn"     — полный VLESS-туннель + DPI + ad-block
     *   "dpi"     — только DPI-обход (без VPN), трафик direct с RU IP
     *   "hybrid"  — VPN+Zapret: всё через VLESS, КРОМЕ выбранных Zapret-сервисов
     *               (они идут direct через TLS-фрагментацию). Быстрый YouTube
     *               + защита VPN для остального.
     */
    var vpnMode: String
        get() = context.prefs().getString("vpn_mode", "vpn") ?: "vpn"
        set(v) { context.prefs().edit().putString("vpn_mode", v).apply() }

    /** Какие сервисы пропускать через DPI-обход (фрагментация TLS).
     *  Доступные значения см. в DpiServices.kt */
    var dpiServices: Set<String>
        get() = context.prefs().getStringSet("dpi_services", emptySet()) ?: emptySet()
        set(v) { context.prefs().edit().putStringSet("dpi_services", v.toHashSet()).apply() }

    /** Стратегия фрагментации (см. TlsFragmenter):
     *   0 — авто-перебор (по умолчанию)
     *   1..12 — конкретная стратегия */
    var dpiStrategy: Int
        get() = context.prefs().getInt("dpi_strategy", 0)
        set(v) { context.prefs().edit().putInt("dpi_strategy", v).apply() }

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

    // ── Onboarding ──────────────────────────────────────────────────────

    /** Показан ли уже welcome-onboarding. После первого "Готово" ставится true. */
    var welcomeShown: Boolean
        get() = context.prefs().getBoolean("welcome_shown", false)
        set(v) { context.prefs().edit().putBoolean("welcome_shown", v).apply() }
}

private fun Context.prefs() = getSharedPreferences("amsales_vpn", Context.MODE_PRIVATE)

val LocalRepository = staticCompositionLocalOf<Repository> {
    error("Repository not provided")
}
