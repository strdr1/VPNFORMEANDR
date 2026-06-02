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
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VpnService с реальным sing-box-движком внутри (libbox).
 *
 * Поток запуска:
 *   1. Repo даёт активный VlessKey
 *   2. Libbox.setup(basePath/workingPath/tempPath) — кладём данные в files/sing-box
 *   3. SingBoxConfig.build(key, repo) → JSON
 *   4. AmSalesPlatformInterface — мост, отдающий TUN-fd через openTun()
 *   5. Libbox.newCommandServer(handler, platform) + server.start()
 *   6. server.startOrReloadService(json, null) — запуск sing-box-инстанса
 *   7. broadcast "on"
 *
 * Stop:
 *   - server.closeService(); server.close(); закрываем TUN.
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

    private val starting = AtomicBoolean(false)
    private var commandServer: CommandServer? = null
    private var platformInterface: AmSalesPlatformInterface? = null
    private var statsJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentTag: String = ""
    private var rxBaseline: Long = 0L
    private var txBaseline: Long = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> scope.launch { connect() }
            ACTION_DISCONNECT -> scope.launch { disconnect() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked")
        scope.launch { disconnect() }
        super.onRevoke()
    }

    override fun onDestroy() {
        scope.launch { disconnect() }
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun connect() {
        if (isRunning || !starting.compareAndSet(false, true)) return

        try {
            val repo = Repository(applicationContext)
            val key = repo.current()?.key ?: run {
                VpnState.broadcast(applicationContext, "error",
                    error = "Сначала добавьте ключ на вкладке Серверы")
                stopSelf()
                return
            }
            currentTag = key.tag
            VpnState.broadcast(applicationContext, "connecting", tag = currentTag)

            // 1. setup путей
            val baseDir = File(applicationContext.filesDir, "sing-box").apply { mkdirs() }
            val workDir = File(baseDir, "work").apply { mkdirs() }
            val tmpDir = File(applicationContext.cacheDir, "sing-box").apply { mkdirs() }
            val setup = SetupOptions().apply {
                basePath = baseDir.absolutePath
                workingPath = workDir.absolutePath
                tempPath = tmpDir.absolutePath
            }
            Libbox.setup(setup)
            Log.i(TAG, "Libbox.setup OK, version=${Libbox.version()}")

            // 2. PlatformInterface — мост из Go в наш VpnService
            val platform = AmSalesPlatformInterface(this, repo.blacklistApps.toSet())
            platformInterface = platform

            // 3. CommandServer — управляет жизненным циклом sing-box
            val handler = object : CommandServerHandler {
                override fun getSystemProxyStatus(): SystemProxyStatus? = null
                override fun serviceReload() { Log.i(TAG, "serviceReload requested") }
                override fun serviceStop() {
                    Log.i(TAG, "serviceStop requested from sing-box")
                    scope.launch { disconnect() }
                }
                override fun setSystemProxyEnabled(enabled: Boolean) {}
                override fun writeDebugMessage(message: String?) {
                    if (!message.isNullOrEmpty()) Log.d("sing-box", message)
                }
            }
            val server = Libbox.newCommandServer(handler, platform)
            server.start()
            commandServer = server
            Log.i(TAG, "CommandServer started")

            // 4. собираем JSON-конфиг и запускаем сервис
            val json = SingBoxConfig.build(key, repo, TUN_MTU)
            Log.d(TAG, "sing-box config: ${json.take(500)}…")

            try {
                Libbox.checkConfig(json)
            } catch (e: Exception) {
                Log.e(TAG, "checkConfig failed: ${e.message}")
                VpnState.broadcast(applicationContext, "error",
                    error = "Невалидный конфиг: ${e.message}")
                cleanup()
                stopSelf()
                return
            }

            server.startOrReloadService(json, null)
            Log.i(TAG, "sing-box service started")

            // 5. foreground notification + статистика
            startForeground(NOTIF_ID, buildNotification(currentTag))
            isRunning = true

            rxBaseline = TrafficStats.getUidRxBytes(applicationInfo.uid).coerceAtLeast(0)
            txBaseline = TrafficStats.getUidTxBytes(applicationInfo.uid).coerceAtLeast(0)
            startStatsWatcher()

            VpnState.broadcast(applicationContext, "on", tag = currentTag)
        } catch (e: Throwable) {
            Log.e(TAG, "connect failed", e)
            VpnState.broadcast(applicationContext, "error",
                error = e.message ?: "Ошибка запуска VPN")
            cleanup()
            stopSelf()
        } finally {
            starting.set(false)
        }
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

    private suspend fun disconnect() {
        if (!isRunning && commandServer == null) return
        try {
            statsJob?.cancel(); statsJob = null
            cleanup()
            stopForeground(STOP_FOREGROUND_REMOVE)
            isRunning = false
            VpnState.broadcast(applicationContext, "off")
            Log.i(TAG, "disconnected")
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "ошибка при отключении", e)
        }
    }

    private fun cleanup() {
        try { commandServer?.closeService() } catch (e: Exception) { Log.w(TAG, "closeService: ${e.message}") }
        try { commandServer?.close() } catch (e: Exception) { Log.w(TAG, "server.close: ${e.message}") }
        commandServer = null

        try { platformInterface?.tunFd?.close() } catch (_: Exception) {}
        platformInterface = null
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
