package com.amsales.vpn.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.amsales.vpn.data.Repository

/**
 * Запускается при загрузке Android. Если у пользователя:
 *   - включена опция "Авто-подключение при старте",
 *   - И есть активный ключ,
 *   - И у нашего пакета уже есть VPN-разрешение (системный диалог уже
 *     показывался хоть раз — VpnService.prepare() вернёт null),
 * то стартуем VPN-сервис прямо отсюда.
 *
 * Если VPN-разрешения нет — ничего не делаем. Пользователь откроет
 * приложение, нажмёт power, получит системный диалог.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != "android.intent.action.QUICKBOOT_POWERON") return

        val repo = Repository(context)
        if (!repo.autoConnectOnBoot) return
        if (repo.current()?.key == null) return

        if (VpnService.prepare(context) == null) {
            val svc = Intent(context, AmSalesVpnService::class.java)
                .setAction(AmSalesVpnService.ACTION_CONNECT)
            context.startForegroundService(svc)
        }
    }
}
