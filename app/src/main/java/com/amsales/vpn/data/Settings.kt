package com.amsales.vpn.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Хранение пользовательских настроек в SharedPreferences.
 *
 *   keys          — список VLESS-ключей (одна строка vless:// на ключ)
 *   currentKey    — индекс активного ключа в списке
 *   blacklistApps — Set<String> с packageName приложений которые ходят мимо VPN
 *
 * Маленькое и тупое хранилище без миграций. Если структура поменяется —
 * перепишем потом.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("amsales_vpn", Context.MODE_PRIVATE)

    var keys: List<String>
        get() = prefs.getString(KEY_LIST, "")?.let {
            if (it.isEmpty()) emptyList() else it.split(SEP)
        } ?: emptyList()
        set(value) {
            prefs.edit().putString(KEY_LIST, value.joinToString(SEP)).apply()
        }

    var currentKeyIndex: Int
        get() = prefs.getInt(KEY_CURRENT, 0)
        set(value) { prefs.edit().putInt(KEY_CURRENT, value).apply() }

    var blacklistApps: Set<String>
        get() = prefs.getStringSet(KEY_BLACKLIST, emptySet()) ?: emptySet()
        set(value) {
            // SharedPreferences иногда мутирует Set — копируем явно.
            prefs.edit().putStringSet(KEY_BLACKLIST, value.toHashSet()).apply()
        }

    /**
     * При первом запуске вшиваем дефолтные ключи AM.SALES. Если у пользователя
     * уже что-то в Store — не трогаем.
     */
    fun ensureDefaults() {
        if (keys.isEmpty()) {
            keys = VlessKeys.defaults
            currentKeyIndex = 0
        }
    }

    companion object {
        private const val KEY_LIST = "keys"
        private const val KEY_CURRENT = "current_key"
        private const val KEY_BLACKLIST = "blacklist_apps"
        // Разделитель списка — vless:// никогда не содержит, безопасно.
        private const val SEP = ""
    }
}
