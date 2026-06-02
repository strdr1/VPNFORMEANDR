package com.amsales.vpn.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.amsales.vpn.R
import com.amsales.vpn.data.Repository
import com.amsales.vpn.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * VpnService — текущая версия:
 *   ✅ Поднимает TUN-интерфейс
 *   ✅ Применяет split tunneling по приложениям
 *   ✅ Показывает foreground notification
 *   ✅ Broadcast'ит состояние в UI (off / connecting / on / error)
 *   ⚠ НЕ запускает реальный sing-box-движок — это ещё в работе.
 *
 * Что нужно добавить для настоящего VPN:
 *   - Подключить libbox AAR (sing-box как Android-библиотека)
 *   - Реализовать PlatformInterface
 *   - Вызвать Libbox.newService(config, platform).start()
 *
 * Прежняя попытка через standalone-sing-box-binary + file_descriptor
 * провалилась: standalone sing-box CLI не умеет принимать TUN-fd от
 * чужого процесса. Нужна именно AAR-библиотека.
 *
 * Пока этот код стоит, можно проверить весь UI, профили, QR, подписки,
 * split tunneling — всё кроме самой передачи трафика.
 */
class AmSalesVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.amsales.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.amsales.vpn.DISCONNECT"
        const val TUN_ADDRESS = "172.19.0.1"
        const val TUN_MTU = 1500
        private const val TAG = "AmSalesVpnService"
        private const val NOTIF_CHANNEL = "amsales_vpn_channel"
        private const val NOTIF_ID = 1
    }

    @Volatile var isRunning: Boolean = false
        private set

    private var tun: ParcelFileDescriptor? = null
    private var statsJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentTag: String = ""
    private var rxBaseline: Long = 0L
    private var txBaseline: Long = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_DISCONNECT -> disconnect()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked")
        disconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private fun connect() {
        if (isRunning) return

        val repo = Repository(applicationContext)
        val key = repo.current()?.key ?: run {
            VpnState.broadcast(applicationContext, "error",
                error = "Сначала добавьте ключ на вкладке Серверы")
            stopSelf()
            return
        }
        currentTag = key.tag
        VpnState.broadcast(applicationContext, "connecting", tag = currentTag)

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(TUN_MTU)
            .addAddress(TUN_ADDRESS, 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("77.88.8.8")

        for (pkg in repo.blacklistApps) {
            try { builder.addDisallowedApplication(pkg) }
            catch (e: Exception) { Log.w(TAG, "skip $pkg: ${e.message}") }
        }
        try { builder.addDisallowedApplication(packageName) }
        catch (_: Exception) {}

        tun = builder.establish()
        if (tun == null) {
            Log.e(TAG, "TUN не поднялся")
            VpnState.broadcast(applicationContext, "error", error = "Не удалось поднять TUN")
            stopSelf()
            return
        }
        Log.i(TAG, "TUN OK fd=${tun?.fd}, ключ=$currentTag")

        startForeground(NOTIF_ID, buildNotification(currentTag))
        isRunning = true

        // ⚠ Реальный sing-box engine пока не запущен — нужен libbox AAR.
        // Текущая сборка валидна как «каркас VPN»: TUN поднят, split
        // tunneling применён, blacklist приложения идут напрямую. Но
        // остальной трафик уходит в TUN и теряется. По прибытию libbox —
        // здесь будет Libbox.newService(config, platform).start().
        Log.w(TAG, "sing-box engine ещё не интегрирован — трафик в туннеле теряется")

        rxBaseline = TrafficStats.getUidRxBytes(applicationInfo.uid).coerceAtLeast(0)
        txBaseline = TrafficStats.getUidTxBytes(applicationInfo.uid).coerceAtLeast(0)
        startStatsWatcher()

        VpnState.broadcast(applicationContext, "on", tag = currentTag)
    }

    private fun startStatsWatcher() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && isRunning) {
                try {
                    val rx = (TrafficStats.getUidRxBytes(applicationInfo.uid) - rxBaseline)
                        .coerceAtLeast(0)
                    val tx = (TrafficStats.getUidTxBytes(applicationInfo.uid) - txBaseline)
                        .coerceAtLeast(0)
                    VpnState.broadcast(applicationContext, "on",
                        tag = currentTag, rx = rx, tx = tx)
                } catch (_: Exception) {}
                delay(1500)
            }
        }
    }

    private fun disconnect() {
        if (!isRunning && tun == null) return
        try {
            statsJob?.cancel(); statsJob = null
            tun?.close(); tun = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            isRunning = false
            VpnState.broadcast(applicationContext, "off")
            stopSelf()
            Log.i(TAG, "disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "ошибка при отключении", e)
        }
    }

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

        val openPI = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectPI = PendingIntent.getService(
            this, 1,
            Intent(this, AmSalesVpnService::class.java).setAction(ACTION_DISCONNECT),
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
