package com.amsales.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.amsales.vpn.R
import com.amsales.vpn.data.Settings
import com.amsales.vpn.data.VlessKey
import com.amsales.vpn.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * VPN-сервис на основе VpnService.
 *
 * Что делает при ACTION_CONNECT:
 *   1. Берёт активный VLESS-ключ из Settings
 *   2. Берёт чёрный список приложений из Settings → передаёт VpnService.Builder
 *      через addDisallowedApplication() — эти приложения ходят мимо TUN.
 *   3. Поднимает TUN-интерфейс
 *   4. Запускает sing-box (бинарь в /data/data/.../files/sing-box) с конфигом
 *      vless+REALITY/WS — он читает TUN и проксирует в VPN.
 *   5. Показывает foreground-уведомление "AM.SALES VPN активен"
 *
 * При ACTION_DISCONNECT — всё аккуратно глушит.
 *
 * ВАЖНО: реальный запуск sing-box-бинаря — это этап 2. На этапе 1 мы
 * поднимаем TUN, но процесс sing-box не стартуем (пока бинарь не положен
 * в assets). Это правильный путь — UI и каркас уже работают.
 */
class AmSalesVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.amsales.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.amsales.vpn.DISCONNECT"
        const val TUN_ADDRESS = "172.19.0.1"
        const val TUN_ROUTE_V4 = "0.0.0.0"
        const val TUN_MTU = 1500
        private const val TAG = "AmSalesVpnService"
        private const val NOTIF_CHANNEL = "amsales_vpn_channel"
        private const val NOTIF_ID = 1
    }

    @Volatile var isRunning: Boolean = false
        private set

    private var tun: ParcelFileDescriptor? = null
    private var singBoxJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentTag: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_DISCONNECT -> disconnect()
            else -> Log.w(TAG, "unknown action: ${intent?.action}")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked by system (другое VPN-приложение перехватило)")
        disconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        disconnect()
        scope.cancel()
        super.onDestroy()
    }

    // ── connect / disconnect ────────────────────────────────────────────

    private fun connect() {
        if (isRunning) {
            Log.i(TAG, "уже подключён")
            return
        }

        val settings = Settings(applicationContext)
        settings.ensureDefaults()

        val keys = settings.keys.mapNotNull { VlessKey.parse(it) }
        if (keys.isEmpty()) {
            Log.e(TAG, "нет ключей")
            return
        }
        val key = keys.getOrNull(settings.currentKeyIndex) ?: keys.first()
        currentTag = key.tag

        // 1) Строим TUN-интерфейс
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(TUN_MTU)
            .addAddress(TUN_ADDRESS, 30)
            .addRoute(TUN_ROUTE_V4, 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("77.88.8.8")

        // 2) Split tunneling: чёрный список приложений → addDisallowedApplication
        val blacklist = settings.blacklistApps
        for (pkg in blacklist) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (e: Exception) {
                Log.w(TAG, "не могу исключить пакет '$pkg': ${e.message}")
            }
        }
        // Сами себя — обязательно мимо туннеля, иначе sing-box не сможет
        // достучаться до VPN-сервера (закольцовка).
        try {
            builder.addDisallowedApplication(packageName)
        } catch (e: Exception) { /* ignore */ }

        tun = builder.establish()
        if (tun == null) {
            Log.e(TAG, "не удалось поднять TUN")
            return
        }
        Log.i(TAG, "TUN поднят, fd=${tun?.fd}, ключ=$currentTag, blacklist=${blacklist.size}")

        // 3) Запуск sing-box (этап 2). Пока заглушка.
        startSingBox(key)

        // 4) Foreground-уведомление
        startForeground(NOTIF_ID, buildNotification(currentTag))
        isRunning = true
    }

    private fun disconnect() {
        if (!isRunning && tun == null) return
        try {
            singBoxJob?.cancel()
            singBoxJob = null
            tun?.close()
            tun = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            isRunning = false
            Log.i(TAG, "disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "ошибка при отключении", e)
        }
    }

    /**
     * Запуск sing-box-бинаря. На этапе 1 — НЕ запускаем (бинаря ещё нет в
     * assets, надо подложить). На этапе 2 — раскомментируем код и положим
     * бинарь в assets/sing-box-arm64 + assets/sing-box-arm32.
     */
    private fun startSingBox(key: VlessKey) {
        singBoxJob = scope.launch {
            // val singBoxPath = ensureSingBoxBinary()
            // val configPath = SingBoxConfig.build(applicationContext, key, tun!!.fd)
            // ProcessBuilder(singBoxPath, "run", "-c", configPath).start()
            Log.i(TAG, "[stub] sing-box запустился бы здесь (этап 2)")
        }
    }

    // ── Notification ────────────────────────────────────────────────────

    private fun buildNotification(tag: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                NOTIF_CHANNEL,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }

        val openIntent = Intent(this, MainActivity::class.java)
            .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val openPI = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, AmSalesVpnService::class.java)
            .setAction(ACTION_DISCONNECT)
        val disconnectPI = PendingIntent.getService(
            this, 1, disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, tag))
            .setContentIntent(openPI)
            .addAction(0, getString(R.string.notif_disconnect), disconnectPI)
            .setOngoing(true)
            .build()
    }
}
