package com.amsales.vpn.tgproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.amsales.vpn.R
import com.amsales.vpn.data.Repository
import com.amsales.vpn.ui.MainActivity

/**
 * Foreground-сервис, запускающий локальный TG-прокси на 127.0.0.1:1443.
 *
 * Совершенно независим от VpnService — может работать одновременно либо
 * сам по себе. Включается из OptionsTab («Включить TG-прокси»).
 */
class TgProxyService : Service() {

    companion object {
        const val ACTION_START = "com.amsales.vpn.TG_START"
        const val ACTION_STOP = "com.amsales.vpn.TG_STOP"
        const val ACTION_STATE = "com.amsales.vpn.TG_STATE"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_LINK = "link"
        const val EXTRA_ERROR = "error"

        private const val TAG = "TgProxyService"
        private const val NOTIF_CHANNEL = "amsales_tg_channel"
        private const val NOTIF_ID = 2

        fun broadcast(ctx: Context, running: Boolean, link: String = "", error: String = "") {
            ctx.sendBroadcast(
                Intent(ACTION_STATE).setPackage(ctx.packageName)
                    .putExtra(EXTRA_RUNNING, running)
                    .putExtra(EXTRA_LINK, link)
                    .putExtra(EXTRA_ERROR, error)
            )
        }
    }

    private var engine: TgProxyEngine? = null
    @Volatile private var currentLink: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
        }
        return START_STICKY
    }

    private fun start() {
        if (engine?.running == true) {
            broadcast(this, true, currentLink)
            return
        }
        try {
            val repo = Repository(applicationContext)
            // Секрет генерим один раз и сохраняем (чтобы ссылка не менялась).
            val secret = repo.tgSecret.ifEmpty {
                val s = TgProxyEngine.generateSecret()
                repo.tgSecret = s
                s
            }
            val workerDomain = repo.tgWorkerDomain
            val fakeTls = repo.tgFakeTlsDomain  // например "www.cloudflare.com" — необязательно

            engine = TgProxyEngine(
                secretHex = secret,
                fakeTlsDomain = fakeTls,
                workerDomain = workerDomain,
                port = 1443,
            )
            currentLink = engine!!.start()
            repo.tgProxyLink = currentLink
            Log.i(TAG, "TG-proxy started: $currentLink")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, buildNotification(currentLink),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, buildNotification(currentLink))
            }
            broadcast(this, true, currentLink)
        } catch (e: Throwable) {
            Log.e(TAG, "TG-proxy start failed", e)
            broadcast(this, false, error = e.message ?: "ошибка запуска")
            stopSelf()
        }
    }

    private fun stop() {
        try { engine?.stop() } catch (_: Exception) {}
        engine = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        broadcast(this, false)
        stopSelf()
    }

    override fun onDestroy() {
        try { engine?.stop() } catch (_: Exception) {}
        engine = null
        broadcast(this, false)
        super.onDestroy()
    }

    private fun buildNotification(link: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                NOTIF_CHANNEL, "TG-прокси", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Telegram прокси-движок"; setShowBadge(false) }
            nm.createNotificationChannel(ch)
        }

        val openPI = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPI = PendingIntent.getService(
            this, 1,
            Intent(this, TgProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AM.SALES TG-прокси")
            .setContentText("Работает · 127.0.0.1:1443")
            .setContentIntent(openPI)
            .addAction(0, "Выключить", stopPI)
            .setOngoing(true)
            .build()
    }
}

data class TgProxyUiState(
    val running: Boolean = false,
    val link: String = "",
    val error: String = "",
)

@Composable
fun rememberTgProxyState(): State<TgProxyUiState> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(TgProxyUiState()) }

    DisposableEffect(ctx) {
        val recv = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent ?: return
                state.value = TgProxyUiState(
                    running = intent.getBooleanExtra(TgProxyService.EXTRA_RUNNING, false),
                    link = intent.getStringExtra(TgProxyService.EXTRA_LINK) ?: "",
                    error = intent.getStringExtra(TgProxyService.EXTRA_ERROR) ?: "",
                )
            }
        }
        val filter = IntentFilter(TgProxyService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(ctx, recv, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(recv, filter)
        }
        onDispose { try { ctx.unregisterReceiver(recv) } catch (_: Exception) {} }
    }
    return state
}
