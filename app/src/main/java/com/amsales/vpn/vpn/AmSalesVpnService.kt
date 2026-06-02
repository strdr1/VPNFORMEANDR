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
import com.amsales.vpn.data.VlessKey
import com.amsales.vpn.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * VpnService — поднимает TUN-интерфейс + запускает sing-box-бинарь,
 * который читает TUN-fd и проксирует трафик через VLESS.
 *
 * Жизненный цикл:
 *   ACTION_CONNECT:
 *     1. Берём активный ключ из Repository (если нет — выходим)
 *     2. Применяем split tunneling через addDisallowedApplication()
 *     3. Поднимаем TUN
 *     4. Распаковываем sing-box.so → cacheDir/sing-box, делаем executable
 *     5. Генерируем конфиг (SingBoxConfig.build) → cacheDir/sing-box-config.json
 *     6. Запускаем sing-box-процесс с этим конфигом
 *     7. Foreground notification
 *
 *   ACTION_DISCONNECT:
 *     1. SIGTERM sing-box (через Process.destroy())
 *     2. Закрываем TUN-fd
 *     3. Снимаем notification
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
    private var singBoxProcess: Process? = null
    private var singBoxJob: Job? = null
    private var statsJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentTag: String = ""
    private var rxBaseline: Long = 0L
    private var txBaseline: Long = 0L

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
        Log.i(TAG, "VPN revoked — другое приложение перехватило")
        disconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        disconnect()
        scope.cancel()
        super.onDestroy()
    }

    // ── Connect ─────────────────────────────────────────────────────────

    private fun connect() {
        if (isRunning) return

        val repo = Repository(applicationContext)
        val key = repo.current()?.key ?: run {
            Log.e(TAG, "нет активного ключа — добавьте сервер на вкладке Серверы")
            VpnState.broadcast(applicationContext, "error",
                error = "Сначала добавьте ключ на вкладке Серверы")
            return
        }
        currentTag = key.tag
        VpnState.broadcast(applicationContext, "connecting", tag = currentTag)

        // 1) Поднимаем TUN
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(TUN_MTU)
            .addAddress(TUN_ADDRESS, 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("77.88.8.8")

        // Split tunneling: исключаем blacklist + сами себя (антизацикливание)
        val blacklist = repo.blacklistApps
        for (pkg in blacklist) {
            try { builder.addDisallowedApplication(pkg) }
            catch (e: Exception) { Log.w(TAG, "не могу исключить '$pkg': ${e.message}") }
        }
        try { builder.addDisallowedApplication(packageName) }
        catch (e: Exception) { /* ignore */ }

        tun = builder.establish()
        if (tun == null) {
            Log.e(TAG, "TUN не поднялся")
            VpnState.broadcast(applicationContext, "error", error = "Не удалось поднять TUN")
            return
        }
        Log.i(TAG, "TUN OK fd=${tun?.fd}, ключ=$currentTag, blacklist=${blacklist.size}")

        // 2) Запускаем sing-box
        startSingBox(key, repo)

        // 3) Foreground notification
        startForeground(NOTIF_ID, buildNotification(currentTag))
        isRunning = true

        // 4) Базовое значение трафика для подсчёта дельты
        rxBaseline = TrafficStats.getUidRxBytes(applicationInfo.uid).coerceAtLeast(0)
        txBaseline = TrafficStats.getUidTxBytes(applicationInfo.uid).coerceAtLeast(0)
        startStatsWatcher()

        VpnState.broadcast(applicationContext, "on", tag = currentTag)
    }

    /** Раз в секунду шлёт broadcast с RX/TX (дельта от baseline). */
    private fun startStatsWatcher() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive && isRunning) {
                try {
                    val rx = (TrafficStats.getUidRxBytes(applicationInfo.uid) - rxBaseline).coerceAtLeast(0)
                    val tx = (TrafficStats.getUidTxBytes(applicationInfo.uid) - txBaseline).coerceAtLeast(0)
                    VpnState.broadcast(applicationContext, "on", tag = currentTag, rx = rx, tx = tx)
                } catch (_: Exception) {}
                delay(1500)
            }
        }
    }

    private fun disconnect() {
        if (!isRunning && tun == null && singBoxProcess == null) return
        try {
            statsJob?.cancel(); statsJob = null
            singBoxJob?.cancel(); singBoxJob = null
            singBoxProcess?.destroy(); singBoxProcess = null
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

    // ── sing-box: распаковка бинаря + запуск ───────────────────────────

    /**
     * Подготовка sing-box: в jniLibs он лежит как libsing-box.so. При
     * установке Android извлекает его в getApplicationInfo().nativeLibraryDir
     * с executable-битом (это критично — обычные файлы из assets не
     * получают +x). Возвращаем путь к нему.
     */
    private fun ensureSingBoxBinary(): File {
        val libDir = applicationInfo.nativeLibraryDir
        val singBox = File(libDir, "libsing-box.so")
        if (!singBox.exists()) {
            throw IOException("libsing-box.so не найден в $libDir")
        }
        return singBox
    }

    private fun startSingBox(key: VlessKey, repo: Repository) {
        val tunFd = tun?.fd ?: return

        singBoxJob = scope.launch {
            try {
                val singBox = ensureSingBoxBinary()
                val configFile = File(cacheDir, "sing-box-config.json")
                configFile.writeText(SingBoxConfig.build(key, repo, tunFd, TUN_MTU))
                Log.i(TAG, "config: ${configFile.absolutePath}")

                val pb = ProcessBuilder(singBox.absolutePath, "run", "-c", configFile.absolutePath)
                    .redirectErrorStream(true)
                    .directory(cacheDir)
                val proc = pb.start()
                singBoxProcess = proc
                Log.i(TAG, "sing-box запущен pid=$proc")

                // Читаем stdout/stderr в лог — если упадёт, увидим причину.
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { Log.i("sing-box", it) }
                }

                // Если процесс умер сам — выключаем VPN-сервис.
                val code = proc.waitFor()
                Log.w(TAG, "sing-box завершился, exit=$code")
                disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "не удалось запустить sing-box", e)
                disconnect()
            }
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
