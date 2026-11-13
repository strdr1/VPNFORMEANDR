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
import com.amsales.vpn.data.ProxyKey
import com.amsales.vpn.data.Repository
import com.amsales.vpn.ui.MainActivity
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
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
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VpnService с реальным sing-box-движком внутри (libbox).
 *
 * Поток запуска:
 *   1. Repo даёт серверы для подключения (выбранный или все — «Авто»)
 *   2. Libbox.setup(basePath/workingPath/tempPath) — кладём данные в files/sing-box
 *   3. SingBoxConfig.build(servers, repo) → JSON
 *   4. AmSalesPlatformInterface — мост, отдающий TUN-fd через openTun()
 *   5. Libbox.newCommandServer(handler, platform) + server.start()
 *   6. server.startOrReloadService(json, null) — запуск sing-box-инстанса
 *   7. broadcast "on", затем delay-тест сквозь туннель (clash API): если
 *      трафик не идёт ни через один сервер — отключаемся с ошибкой
 *
 * Stop:
 *   - server.closeService(); server.close(); закрываем TUN.
 */
class AmSalesVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.amsales.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.amsales.vpn.DISCONNECT"
        const val ACTION_SWITCH_MODE = "com.amsales.vpn.SWITCH_MODE"
        /** Переподключиться с текущими настройками (смена сервера / «Авто»). */
        const val ACTION_RECONNECT = "com.amsales.vpn.RECONNECT"
        const val TUN_ADDRESS = "172.19.0.1"
        const val TUN_MTU = 1500
        const val DPI_PORT = 8443  // локальный SOCKS5 для DPI-обхода
        const val VPN_BRIDGE_PORT = 9444  // локальный SOCKS5 → VLESS-туннель (Happy Eyeballs fallback)
        private const val TAG = "AmSalesVpnService"
        private const val NOTIF_CHANNEL = "amsales_vpn_channel"
        private const val NOTIF_ID = 1
    }

    @Volatile var isRunning: Boolean = false
        private set

    private val starting = AtomicBoolean(false)
    private var commandServer: CommandServer? = null
    private var platformInterface: AmSalesPlatformInterface? = null
    private var dpiServer: com.amsales.vpn.dpi.DpiSocksServer? = null
    private var statsJob: Job? = null
    private var checkJob: Job? = null
    private var activeServers: List<ProxyKey> = emptyList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentTag: String = ""
    private var rxBaseline: Long = 0L
    private var txBaseline: Long = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> scope.launch { connect() }
            ACTION_DISCONNECT -> scope.launch { disconnect() }
            ACTION_RECONNECT -> scope.launch {
                if (!isRunning) return@launch
                disconnect()
                delay(300)
                connect()
            }
            ACTION_SWITCH_MODE -> scope.launch {
                // Цикл режимов: vpn → hybrid → dpi → vpn
                val repo = Repository(applicationContext)
                repo.vpnMode = when (repo.vpnMode) {
                    "vpn" -> "hybrid"
                    "hybrid" -> "dpi"
                    else -> "vpn"
                }
                disconnect()
                kotlinx.coroutines.delay(300)
                connect()
            }
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

        val crashLog = File(applicationContext.filesDir, "crash.log")
        fun stage(name: String, block: () -> Unit) {
            try {
                Log.i(TAG, ">>> $name")
                block()
                Log.i(TAG, "<<< $name OK")
            } catch (t: Throwable) {
                val msg = "FAIL at [$name]: ${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, msg, t)
                crashLog.appendText("\n[${System.currentTimeMillis()}] $msg\n" +
                    t.stackTraceToString() + "\n")
                throw t
            }
        }

        try {
            val repo = Repository(applicationContext)
            val isDpiOnly = repo.vpnMode == "dpi"
            val isHybrid = repo.vpnMode == "hybrid"

            // В DPI-only и Hybrid если юзер ничего не выбрал — авто-YouTube,
            // иначе режимы бесполезны.
            if ((isDpiOnly || isHybrid) && repo.dpiServices.isEmpty()) {
                repo.dpiServices = setOf("youtube")
                Log.i(TAG, "mode=${repo.vpnMode}: auto-selected YouTube")
            }

            // DPI-only режим не требует ключей.
            val servers = if (isDpiOnly) emptyList() else repo.serversForConnect()
            if (!isDpiOnly && servers.isEmpty()) {
                val cur = repo.current()?.key
                VpnState.broadcast(applicationContext, "error", error = when {
                    repo.profiles().isEmpty() -> "Сначала добавьте ключ на вкладке Серверы"
                    cur != null && !cur.isSupported ->
                        "Сервер «${cur.tag}» использует «${cur.unsupported}» — это не поддерживается. Выберите другой сервер или «Авто»"
                    else -> "Нет серверов, которые поддерживает приложение"
                })
                stopSelf()
                return
            }
            activeServers = servers
            val serversLabel = when {
                servers.size > 1 -> "Авто"
                else -> servers.firstOrNull()?.tag ?: "?"
            }
            currentTag = when {
                isDpiOnly -> "DPI-обход"
                isHybrid -> "VPN + Запрет ($serversLabel)"
                else -> serversLabel
            }
            VpnState.broadcast(applicationContext, "connecting", tag = currentTag)

            // 1. перенаправляем Go-stderr в файл (для Go-паник)
            //
            // Базовый каталог делаем УНИКАЛЬНЫМ на каждый запуск —
            // sing-box не подхватит DNS-кеш / connection-pool / TLS-session
            // с предыдущего запуска. Это решает баг "выключил YouTube из
            // списка а трафик всё равно идёт старым путём".
            val rootDir = File(applicationContext.filesDir, "sing-box").apply { mkdirs() }
            // Удаляем все старые сессионные подпапки (старше 1 минуты)
            try {
                val now = System.currentTimeMillis()
                rootDir.listFiles()?.forEach { f ->
                    if (f.isDirectory && f.name.startsWith("session-")) {
                        if (now - f.lastModified() > 60_000) {
                            f.deleteRecursively()
                        }
                    }
                }
            } catch (_: Exception) {}

            val sessionId = System.currentTimeMillis().toString()
            val baseDir = File(rootDir, "session-$sessionId").apply { mkdirs() }
            val workDir = File(baseDir, "work").apply { mkdirs() }
            val tmpDir = File(applicationContext.cacheDir, "sing-box-$sessionId").apply { mkdirs() }
            val stderrPath = File(applicationContext.filesDir, "stderr.log").absolutePath

            stage("redirectStderr") {
                try { Libbox.redirectStderr(stderrPath) }
                catch (e: Throwable) { Log.w(TAG, "redirectStderr fail: ${e.message}") }
            }

            stage("Libbox.setup") {
                val setup = SetupOptions().apply {
                    basePath = baseDir.absolutePath
                    workingPath = workDir.absolutePath
                    // как у SagerNet (sing-box-for-android):
                    fixAndroidStack = true
                    logMaxLines = 3000
                    debug = false
                    tempPath = tmpDir.absolutePath
                }
                Libbox.setup(setup)
                Log.i(TAG, "version=${Libbox.version()}")
            }

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
            lateinit var server: CommandServer
            stage("newCommandServer") {
                server = Libbox.newCommandServer(handler, platform)
            }
            stage("CommandServer.start") {
                server.start()
            }
            commandServer = server

            // 4. Если режим DPI-only или Hybrid — поднимаем локальный
            // TLS-фрагментирующий SOCKS5-прокси. В чистом VPN-режиме
            // он не нужен — Zapret-сервисы пойдут через VLESS-туннель.
            val needDpi = isDpiOnly || (isHybrid && repo.dpiServices.isNotEmpty())
            if (needDpi) {
                stage("DPI SOCKS server start") {
                    // Применяем выбранную юзером стратегию (0 = авто-перебор)
                    com.amsales.vpn.dpi.TlsFragmenter.currentStrategy = repo.dpiStrategy
                    com.amsales.vpn.dpi.TlsFragmenter.resetGoodStrategy()
                    Log.i(TAG, "DPI strategy: ${repo.dpiStrategy} (${com.amsales.vpn.dpi.TlsFragmenter.strategyName(repo.dpiStrategy)})")
                    // Передаём this (VpnService) — DpiSocksServer вызовет
                    // protect() на каждом upstream-сокете, чтобы он шёл
                    // через wlan0 в обход нашего же TUN.
                    // vpnBridgePort — только в Hybrid режиме (там VLESS активен).
                    // В DPI-only нет VLESS-туннеля, race недоступен.
                    val bridgePort = if (isHybrid) VPN_BRIDGE_PORT else null
                    val ds = com.amsales.vpn.dpi.DpiSocksServer(DPI_PORT, this@AmSalesVpnService, bridgePort)
                    ds.start()
                    dpiServer = ds
                }
            }

            // 5. собираем JSON-конфиг — VPN или DPI-only
            // sing-box internal лог пишется в этот файл (stderr-redirect
            // на gobind не работает — пишет в os.Stderr который не виден)
            val singboxLogPath = File(applicationContext.filesDir, "singbox.log").absolutePath
            var json = if (isDpiOnly) {
                SingBoxConfig.buildDpiOnly(repo, TUN_MTU, singboxLogPath)
            } else {
                SingBoxConfig.build(servers, repo, TUN_MTU, singboxLogPath, enableDpiBypass = isHybrid)
            }
            val modeLabel = when { isDpiOnly -> "DPI"; isHybrid -> "VPN+ZAPRET"; else -> "VPN" }
            Log.d(TAG, "sing-box config (mode=$modeLabel):\n$json")

            // Если libbox собран без clash API, конфиг с ним не пройдёт
            // проверку — тогда работаем без него и без проверки связи.
            var clashApi = !isDpiOnly
            try {
                Libbox.checkConfig(json)
            } catch (e: Exception) {
                if (clashApi && e.message.orEmpty().contains("clash", ignoreCase = true)) {
                    Log.w(TAG, "clash API unavailable, connecting without tunnel check: ${e.message}")
                    clashApi = false
                    json = SingBoxConfig.build(servers, repo, TUN_MTU, singboxLogPath,
                        enableDpiBypass = isHybrid, clashApi = false)
                }
            }
            stage("Libbox.checkConfig") {
                Libbox.checkConfig(json)
            }

            stage("startOrReloadService") {
                // ВАЖНО: nil тут падает в Go-коде sing-box 1.13.12
                // (s.StartedService.StartOrReloadService(.., &daemon.OverrideOptions{
                //   AutoRedirect: options.AutoRedirect, ...}) — без nil-check).
                // Передаём пустой OverrideOptions с дефолтами.
                val overrides = OverrideOptions().apply {
                    autoRedirect = false
                }
                server.startOrReloadService(json, overrides)
            }

            // 5. foreground notification + статистика. На Android 14+
            // обязателен FOREGROUND_SERVICE_TYPE для специального типа.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, buildNotification(currentTag),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, buildNotification(currentTag))
            }
            isRunning = true

            rxBaseline = TrafficStats.getUidRxBytes(applicationInfo.uid).coerceAtLeast(0)
            txBaseline = TrafficStats.getUidTxBytes(applicationInfo.uid).coerceAtLeast(0)
            startStatsWatcher()

            VpnState.broadcast(applicationContext, "on", tag = currentTag)
            // В DPI-only нет прокси-outbound и clash API — проверять нечего.
            if (clashApi) startTunnelCheck(isHybrid)
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

    // ── Проверка связи сквозь туннель ───────────────────────────────────
    //
    // TUN сам подтверждает TCP-рукопожатия локально, поэтому «процесс
    // запущен» ещё не значит «трафик идёт». Делаем настоящий HTTP-запрос
    // через outbound "proxy" (clash API delay). В «Авто» первая попытка
    // сначала перемеряет всю группу, чтобы urltest ушёл с мёртвого сервера.
    // 3 честных «трафик не идёт» подряд → отключаемся с ошибкой, чтобы
    // вернуть интернет. Если же сама проверка не смогла достучаться до
    // clash API — туннель не трогаем: неизвестно ≠ не работает.

    private class ClashResp(val code: Int, val body: String)

    /**
     * GET к clash API через голый сокет, HTTP/1.0 (сервер закрывает
     * соединение после ответа). Не HttpURLConnection: при targetSdk 28+
     * Android запрещает cleartext HTTP, и запрос к http://127.0.0.1 падал
     * ещё до отправки — проверка считала туннель мёртвым и рвала его.
     * null — API недоступен (не знаем, есть ли трафик).
     */
    private fun clashGet(path: String, timeoutMs: Int): ClashResp? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", SingBoxConfig.CLASH_API_PORT), 3000)
            s.soTimeout = timeoutMs
            s.getOutputStream().write(
                "GET $path HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(Charsets.US_ASCII))
            val resp = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            val code = resp.substringAfter(' ', "").take(3).toIntOrNull() ?: return@use null
            ClashResp(code, resp.substringAfter("\r\n\r\n", ""))
        }
    } catch (_: Exception) {
        null
    }

    /** >0 — задержка в мс; 0 — API ответил, что трафик не прошёл;
     *  -1 — API недоступен (результат неизвестен). */
    private fun proxyDelay(groupTest: Boolean): Int {
        val q = "?url=" + URLEncoder.encode(SingBoxConfig.TEST_URL, "UTF-8") + "&timeout=6000"
        // Ответ группы не показателен (пока идёт её собственная проверка,
        // sing-box отдаёт {}), решение — по delay самого "proxy".
        if (groupTest) clashGet("/group/proxy/delay$q", 15_000)
        var unreachable = true
        // Второй адрес — на случай, если первый недоступен именно через этот сервер.
        for (url in listOf(SingBoxConfig.TEST_URL, "http://cp.cloudflare.com/generate_204")) {
            val qs = "?url=" + URLEncoder.encode(url, "UTF-8") + "&timeout=6000"
            val r = clashGet("/proxies/proxy/delay$qs", 9_000) ?: continue
            unreachable = false
            if (r.code != 200) continue
            val d = try { JSONObject(r.body).optInt("delay", 0) } catch (_: Exception) { 0 }
            if (d > 0) return d
        }
        return if (unreachable) -1 else 0
    }

    /** Какой сервер сейчас несёт трафик (для «Авто» — выбор urltest). */
    private fun refreshActiveTag(isHybrid: Boolean) {
        val servers = activeServers
        val name = if (servers.size <= 1) {
            servers.firstOrNull()?.tag
        } else {
            val now = clashGet("/proxies/proxy", 4000)?.let {
                try { JSONObject(it.body).optString("now") } catch (_: Exception) { "" }
            }.orEmpty()
            now.removePrefix("s").toIntOrNull()?.let { servers.getOrNull(it)?.tag }
        } ?: return
        val label = if (servers.size > 1) "Авто → $name" else name
        currentTag = if (isHybrid) "VPN + Запрет ($label)" else label
        VpnState.broadcast(applicationContext, "on", tag = currentTag)
    }

    private fun startTunnelCheck(isHybrid: Boolean) {
        checkJob?.cancel()
        checkJob = scope.launch {
            delay(2500)
            var failures = 0
            for (attempt in 0 until 3) {
                if (!isRunning) return@launch
                val d = proxyDelay(groupTest = activeServers.size > 1 && attempt == 0)
                if (d < 0) {
                    Log.w(TAG, "tunnel check: clash API unreachable, attempt $attempt")
                } else if (d == 0) {
                    failures++
                }
                if (d > 0) {
                    Log.i(TAG, "tunnel check OK: ${d}ms")
                    refreshActiveTag(isHybrid)
                    // urltest называет выбранный сервер только когда домерит
                    // все (мёртвые — по таймауту) — уточняем имя чуть позже.
                    if (activeServers.size > 1) {
                        delay(8000)
                        if (isRunning) refreshActiveTag(isHybrid)
                    }
                    return@launch
                }
                delay(2000)
            }
            if (!isRunning) return@launch
            // Рвём туннель, только если API трижды ответил «трафик не прошёл».
            if (failures < 3) {
                Log.w(TAG, "tunnel check inconclusive ($failures failures) — keeping tunnel")
                return@launch
            }
            val many = activeServers.size > 1
            Log.w(TAG, "tunnel check failed — disconnecting")
            disconnect()
            VpnState.broadcast(applicationContext, "error", error = if (many)
                "Ни один сервер не ответил. Обновите подписку или попробуйте другой интернет (Wi-Fi/мобильный)."
            else
                "Сервер «${activeServers.firstOrNull()?.tag ?: "?"}» не пропускает трафик. Выберите другой сервер или «Авто».")
        }
    }

    private suspend fun disconnect() {
        if (!isRunning && commandServer == null) return
        try {
            checkJob?.let { if (it != kotlinx.coroutines.currentCoroutineContext()[Job]) it.cancel() }
            checkJob = null
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
        try { dpiServer?.stop() } catch (_: Exception) {}
        dpiServer = null
        // Чистим session-папки старее 30 секунд (не текущую — sing-box ещё
        // может писать в неё лог).
        try {
            val rootDir = File(applicationContext.filesDir, "sing-box")
            val cutoff = System.currentTimeMillis() - 30_000
            rootDir.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name.startsWith("session-")
                    && f.lastModified() < cutoff) {
                    f.deleteRecursively()
                }
            }
            val cacheRoot = applicationContext.cacheDir
            cacheRoot.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name.startsWith("sing-box-")
                    && f.lastModified() < cutoff) {
                    f.deleteRecursively()
                }
            }
        } catch (_: Exception) {}
        try { commandServer?.closeService() } catch (e: Exception) { Log.w(TAG, "closeService: ${e.message}") }
        // Не закрываем тут tunFd сразу — sing-box дюпает fd на Go-side,
        // и его горутины могут ещё использовать наш fd 500ms-1s после
        // closeService(). Закроем чуть позже.
        val pfd = platformInterface?.tunFd
        try { commandServer?.close() } catch (e: Exception) { Log.w(TAG, "server.close: ${e.message}") }
        commandServer = null
        platformInterface = null

        if (pfd != null) {
            scope.launch {
                kotlinx.coroutines.delay(1000)
                try { pfd.close() } catch (_: Exception) {}
            }
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
        val switchPI = PendingIntent.getService(
            this, 2,
            Intent(this, AmSalesVpnService::class.java).setAction(ACTION_SWITCH_MODE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val repo = Repository(applicationContext)
        val modeLabel = when (repo.vpnMode) {
            "dpi" -> "Zapret"
            "hybrid" -> "VPN + Zapret"
            else -> "VPN"
        }
        // Кнопка циклически перебирает 3 режима: VPN → Hybrid → Zapret → VPN
        val switchLabel = when (repo.vpnMode) {
            "vpn" -> "VPN+Zapret"
            "hybrid" -> "Zapret"
            else -> "VPN"
        }

        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("AM.SALES $modeLabel активен")
            .setContentText(getString(R.string.notif_text, tag))
            .setContentIntent(openPI)
            .addAction(0, switchLabel, switchPI)
            .addAction(0, getString(R.string.notif_disconnect), disconnectPI)
            .setOngoing(true)
            .build()
    }
}
