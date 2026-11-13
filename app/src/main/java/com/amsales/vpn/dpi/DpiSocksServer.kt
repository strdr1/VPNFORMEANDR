package com.amsales.vpn.dpi

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Локальный SOCKS5-сервер на 127.0.0.1:<port>.
 *
 * Sing-box подключается к нам через socks outbound — мы делаем настоящий
 * TCP-коннект к destination и пробрасываем байты В ОБЕ СТОРОНЫ. Первый
 * клиентский исходящий пакет фрагментируем (TLS Client Hello — режется
 * на 2 части посредине SNI), остальное идёт как есть.
 *
 * SOCKS5 поддерживается частично: только TCP CONNECT с NO_AUTH.
 */
class DpiSocksServer(
    private val port: Int,
    /** Если задан — будем protect()-ить каждый upstream-сокет.
     *  Без этого наш собственный коннект к Google попадает обратно в TUN
     *  и теряется в loop'е. */
    private val vpnService: android.net.VpnService? = null,
    /** Если задан — Happy Eyeballs fallback: параллельно с direct-сокетом
     *  открываем сокет к localhost:vpnBridgePort (sing-box inbound который
     *  отправляет в VLESS). Первый сокет получивший данные выигрывает,
     *  второй закрывается. Это решает обрывы когда DPI-фрагментация не
     *  пробивает — VPN-путь возьмёт коннект на себя.
     *  null = старое поведение (только direct). */
    private val vpnBridgePort: Int? = null,
) {

    @Volatile var running: Boolean = false
        private set

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null

    // Семафор ограничивает количество одновременных коннектов через bypass.
    // 12 параллельных — при success rate ~20% (strategy=14/6) это даёт
    // 2-3 одновременно живых коннекта = плавный поток.
    private val connSemaphore = kotlinx.coroutines.sync.Semaphore(12)

    // Per-IP блеклист direct'а: если IP 2 раза подряд провалил direct
    // (TIMEOUT/handshake-only), на 60 сек пропускаем holdoff и идём сразу в VPN.
    private data class IpHealth(val failures: Int, val skipDirectUntil: Long)
    private val ipHealth = java.util.concurrent.ConcurrentHashMap<String, IpHealth>()
    private fun isDirectBlacklisted(host: String): Boolean {
        val h = ipHealth[host] ?: return false
        return System.currentTimeMillis() < h.skipDirectUntil
    }
    private fun recordDirectFailure(host: String) {
        val prev = ipHealth[host]
        val fails = (prev?.failures ?: 0) + 1
        // 3 провала подряд → блеклист на 30 сек.
        // (Раньше было 2/60с — слишком агрессивно, IP'шки сразу шли в VPN
        // → реклама. На мобильной сети Zapret работает, не блеклистим рано.)
        val until = if (fails >= 3) System.currentTimeMillis() + 30_000 else 0L
        ipHealth[host] = IpHealth(fails, until)
    }
    private fun recordDirectSuccess(host: String) {
        ipHealth.remove(host)
    }

    // VPN-health: если VPN сам массово таймаутится (например VLESS-сервер
    // отдаёт ServerHello но ApplicationData не доходит из-за MTU/REALITY-edge-case),
    // временно отключаем VPN-fallback — пусть идёт чистый Zapret.
    // Без этого YouTube ретраит через мёртвый VPN, видео грузит туго.
    private val vpnFailStreak = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var vpnDisabledUntil: Long = 0L
    private fun isVpnDisabled(): Boolean = System.currentTimeMillis() < vpnDisabledUntil
    private fun recordVpnOutcome(success: Boolean) {
        if (success) {
            vpnFailStreak.set(0)
        } else {
            val streak = vpnFailStreak.incrementAndGet()
            if (streak >= 5 && !isVpnDisabled()) {
                vpnDisabledUntil = System.currentTimeMillis() + 60_000
                Log.w(TAG, "VPN unhealthy ($streak timeouts) → disabling VPN fallback for 60s, pure Zapret")
                vpnFailStreak.set(0)
            }
        }
    }

    // TIMEOUT-storm detector: скользящее окно последних 20 direct-коннектов.
    // Если ≥14 (70%) провалов — массовый DPI-удар. Переходим в STORM-режим
    // на 60 сек: ВЕСЬ трафик идёт сразу через VPN, direct не открываем.
    // Через 60 сек снова пробуем — если шторм продолжается, ещё 60 сек.
    // Это решает "залипание" YouTube когда DPI режет всё подряд.
    private val recentDirectResults = java.util.concurrent.ConcurrentLinkedDeque<Boolean>()
    @Volatile private var stormUntil: Long = 0L
    private fun isStorm(): Boolean = System.currentTimeMillis() < stormUntil
    private fun recordDirectOutcome(success: Boolean) {
        recentDirectResults.addLast(success)
        while (recentDirectResults.size > 20) recentDirectResults.pollFirst()
        if (recentDirectResults.size >= 15) {
            val failures = recentDirectResults.count { !it }
            val ratio = failures.toDouble() / recentDirectResults.size
            // 85% fail в окне из 15+. НО только если VPN живой — иначе
            // STORM закрывает direct, vpnUnhealthy закрывает VPN, получается
            // deadlock и ничего не работает. Лучше дать direct шанс.
            if (ratio >= 0.85 && !isStorm() && !isVpnDisabled()) {
                stormUntil = System.currentTimeMillis() + 30_000
                Log.w(TAG, "STORM detected: ${failures}/${recentDirectResults.size} direct failures → VPN-only for 30s")
                recentDirectResults.clear()
            }
        }
    }

    fun start() {
        if (running) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("127.0.0.1", port))
        serverSocket = ss
        running = true

        acceptJob = scope.launch {
            Log.i(TAG, "DPI SOCKS5 listening on 127.0.0.1:$port (waiting for accept...)")
            // Самодиагностика: проверяем что raw-TCP до Google вообще работает,
            // и что setsockopt IP_TTL поддерживается (без него TTL-стратегии
            // молча превращаются в обычный split).
            scope.launch { selfDiagnostics() }
            while (isActive && running) {
                try {
                    val client = withContext(Dispatchers.IO) { ss.accept() }
                    client.tcpNoDelay = true
                    Log.i(TAG, "ACCEPTED from ${client.inetAddress?.hostAddress}:${client.port}")
                    scope.launch {
                        // Rate-limit: ждём слот, чтобы не спамить много
                        // параллельных handshake'ов. Это снижает шанс
                        // попасть в conntrack-карантин РКН.
                        connSemaphore.withPermit {
                            handle(client)
                        }
                    }
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "accept: ${e.message}")
                    break
                }
            }
        }
    }

    /** Однократная диагностика при старте — помогает понять что происходит. */
    private suspend fun selfDiagnostics() = withContext(Dispatchers.IO) {
        try {
            kotlinx.coroutines.delay(2000)  // ждём пока VPN-сервис активен
            Log.i(TAG, "=== SELF-DIAGNOSTICS ===")
            // 1. TCP connect до Google NS
            val t0 = System.currentTimeMillis()
            val testSocket = Socket()
            try {
                testSocket.bind(InetSocketAddress(0))
                val protected = try { vpnService?.protect(testSocket) ?: true } catch (_: Exception) { false }
                testSocket.connect(InetSocketAddress("216.239.36.223", 443), 5000)
                val dt = System.currentTimeMillis() - t0
                Log.i(TAG, "[DIAG] TCP 216.239.36.223:443 OK in ${dt}ms (protected=$protected)")
            } catch (e: Exception) {
                Log.e(TAG, "[DIAG] TCP 216.239.36.223:443 FAILED: ${e.message}")
            } finally {
                try { testSocket.close() } catch (_: Exception) {}
            }
            // 2. Проверяем что setsockopt IP_TTL работает (через ParcelFileDescriptor)
            val ttlSocket = Socket()
            try {
                ttlSocket.bind(InetSocketAddress(0))
                try { vpnService?.protect(ttlSocket) } catch (_: Exception) {}
                ttlSocket.connect(InetSocketAddress("216.239.36.223", 443), 5000)
                val pfd = android.os.ParcelFileDescriptor.fromSocket(ttlSocket)
                try {
                    android.system.Os.setsockoptInt(
                        pfd.fileDescriptor,
                        android.system.OsConstants.IPPROTO_IP,
                        android.system.OsConstants.IP_TTL,
                        3,
                    )
                    Log.i(TAG, "[DIAG] setsockopt IP_TTL=3 OK — TTL-стратегии (6,7,8,9,11) РАБОТОСПОСОБНЫ")
                } catch (e: Throwable) {
                    Log.e(TAG, "[DIAG] setsockopt IP_TTL FAILED: ${e.message} — TTL-стратегии БЕСПОЛЕЗНЫ, останутся 1-5,10,12")
                }
                try { pfd.detachFd() } catch (_: Throwable) {}
            } catch (e: Exception) {
                Log.w(TAG, "[DIAG] TTL test setup failed: ${e.message}")
            } finally {
                try { ttlSocket.close() } catch (_: Exception) {}
            }
            Log.i(TAG, "=== /SELF-DIAGNOSTICS ===")
        } catch (e: Exception) {
            Log.w(TAG, "[DIAG] crashed: ${e.message}")
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        acceptJob?.cancel()
        scope.cancel()
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 30000
            val cin = DataInputStream(client.getInputStream())
            val cout = client.getOutputStream()

            Log.d(TAG, "handle: reading greeting")
            // 1. Greeting: VER(1) NMETHODS(1) METHODS(N)
            val ver = cin.read()
            if (ver != 0x05) {
                Log.w(TAG, "not socks5: ver=$ver")
                throw IOException("not socks5: $ver")
            }
            val nm = cin.read()
            val methods = ByteArray(nm)
            cin.readFully(methods)
            Log.d(TAG, "greeting OK, methods=${methods.size}")
            // 2. Метод: NO_AUTH (0x00)
            cout.write(byteArrayOf(0x05, 0x00)); cout.flush()

            // 3. Request: VER(1) CMD(1) RSV(1) ATYP(1) DST.ADDR DST.PORT(2)
            val v2 = cin.read()
            val cmd = cin.read()
            cin.read()  // RSV
            val atyp = cin.read()
            Log.d(TAG, "request: v=$v2 cmd=$cmd atyp=$atyp")
            if (v2 != 0x05 || cmd != 0x01) {
                Log.w(TAG, "unsupported cmd: $cmd")
                reply(cout, 0x07)  // command not supported
                return
            }
            val host = when (atyp) {
                0x01 -> {  // IPv4
                    val b = ByteArray(4); cin.readFully(b)
                    InetAddress.getByAddress(b).hostAddress
                }
                0x03 -> {  // domain
                    val ln = cin.read()
                    val b = ByteArray(ln); cin.readFully(b)
                    String(b, Charsets.US_ASCII)
                }
                0x04 -> {  // IPv6
                    val b = ByteArray(16); cin.readFully(b)
                    InetAddress.getByAddress(b).hostAddress
                }
                else -> {
                    reply(cout, 0x08); return  // address type not supported
                }
            } ?: run { reply(cout, 0x01); return }
            val port = (cin.read() shl 8) or cin.read()

            Log.i(TAG, "→ upstream $host:$port")
            // 4. Happy Eyeballs v2 для DPI: открываем ОБА пути (direct + VPN-bridge).
            // TCP-connect — не критерий: DPI пропускает TCP handshake, режет TLS.
            // Реальный race решается ниже после отправки Client Hello — побеждает
            // тот кто первым прислал ServerHello.
            val pair = openBothPaths(host, port)
            if (pair.direct == null && pair.vpn == null) {
                Log.w(TAG, "both paths failed to connect $host:$port")
                reply(cout, 0x05); return
            }
            try { pair.direct?.soTimeout = 8000 } catch (_: Exception) {}
            try { pair.vpn?.soTimeout = 8000 } catch (_: Exception) {}

            // 5. Reply success клиенту: VER(1) REP(1) RSV(1) ATYP(1) BND.ADDR BND.PORT(2)
            cout.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            cout.flush()

            // 6. Читаем первый chunk от клиента (Client Hello — до 300мс / 8KB).
            val firstChunk = java.io.ByteArrayOutputStream()
            val firstReadDeadline = System.currentTimeMillis() + 300
            run {
                val buf = ByteArray(16384)
                while (System.currentTimeMillis() < firstReadDeadline && firstChunk.size() < 8192) {
                    try { client.soTimeout = 100 } catch (_: Exception) {}
                    val n = try { cin.read(buf) } catch (_: java.net.SocketTimeoutException) { 0 }
                    if (n < 0) break
                    if (n > 0) {
                        firstChunk.write(buf, 0, n)
                        if (isTlsRecordComplete(firstChunk.toByteArray())) break
                    }
                }
                try { client.soTimeout = 0 } catch (_: Exception) {}
            }
            val helloBytes = firstChunk.toByteArray()
            val connectStart = System.currentTimeMillis()

            // 7. DIRECT-PRIORITY race: Zapret должен выиграть если способен.
            // Зачем: VPN-путь идёт через US-сервер → Google отдаёт US-рекламу.
            // В РФ рекламы нет, поэтому direct (Zapret) предпочтителен.
            //
            // Логика:
            //   · Client Hello в DIRECT шлётся СРАЗУ (с фрагментацией)
            //   · Client Hello в VPN держится 800мс — даём Zapret шанс
            //   · Если direct прислал Server Hello раньше 800мс → VPN
            //     не отправляет Hello, закрывается. Zapret wins без затрат
            //     VPN-трафика.
            //   · Если direct молчит >800мс → шлём Hello в VPN,
            //     дальше первый кто ответил — winner.
            // 5с holdoff: даём Zapret реальный шанс. На мобильной сети
            // direct иногда отвечает через 3-4с (мобильная задержка), и если
            // holdoff был 2.5с — VPN успевал перехватить → реклама.
            // Если direct полностью молчит >5с → реально мёртв → fallback.
            // Для blacklisted IP (3+ провалов) — holdoff=0, сразу race.
            val VPN_HOLDOFF_MS: Long = if (isDirectBlacklisted(host)) {
                Log.i(TAG, "host=$host blacklisted → holdoff=0")
                0L
            } else 5000L
            var usedStrategy = 0
            val directS = pair.direct
            val vpnS = pair.vpn
            val vpnHelloSent = java.util.concurrent.atomic.AtomicBoolean(false)
            val directWon = java.util.concurrent.atomic.AtomicBoolean(false)
            // Live-счётчик принятых от direct байт — VPN-thread смотрит чтобы
            // отличить "реально работает" от "ServerHello и сдох".
            val firstReplyChunkSize = java.util.concurrent.atomic.AtomicInteger(0)

            // Отправка в direct (с фрагментацией) — СРАЗУ.
            val directSendThread = if (directS != null && helloBytes.isNotEmpty()) {
                Thread({
                    try {
                        val out = directS.getOutputStream()
                        usedStrategy = TlsFragmenter.sendWithFragmentation(directS, out, helloBytes)
                    } catch (_: Exception) {
                        try { directS.close() } catch (_: Exception) {}
                    }
                }, "race-send-direct").also { it.start() }
            } else null

            // Отправка в VPN — с задержкой. Отменяется если direct победил.
            val vpnSendThread = if (vpnS != null && helloBytes.isNotEmpty()) {
                Thread({
                    try {
                        val tEnd = System.currentTimeMillis() + VPN_HOLDOFF_MS
                        while (System.currentTimeMillis() < tEnd && !directWon.get()) {
                            Thread.sleep(50)
                        }
                        if (directWon.get()) {
                            try { vpnS.close() } catch (_: Exception) {}
                            return@Thread
                        }
                        val out = vpnS.getOutputStream()
                        out.write(helloBytes); out.flush()
                        vpnHelloSent.set(true)
                    } catch (_: Exception) {
                        try { vpnS.close() } catch (_: Exception) {}
                    }
                }, "race-send-vpn").also { it.start() }
            } else null

            // Polling: первый кто получил ≥1 байт = winner.
            val winner: Socket
            val viaVpn: Boolean
            val firstReplyChunk = java.io.ByteArrayOutputStream()
            run {
                val deadline = System.currentTimeMillis() + 12000
                val tmp = ByteArray(16384)
                var pickedDirect: Socket? = null
                var pickedVpn: Socket? = null
                try { directS?.soTimeout = 80 } catch (_: Exception) {}
                try { vpnS?.soTimeout = 80 } catch (_: Exception) {}
                // Direct побеждает сразу с первого байта — если данные пошли,
                // считаем что Zapret работает. Даже если потом DPI порежет
                // ApplicationData — это лучше чем сразу врубать VPN с рекламой.
                // VPN включается ТОЛЬКО если direct совсем молчит 2.5с
                // (VPN_HOLDOFF_MS) — это уже регулируется vpnHelloSent.
                while (System.currentTimeMillis() < deadline) {
                    if (directS != null && pickedDirect == null && !directS.isClosed) {
                        try {
                            val n = directS.getInputStream().read(tmp)
                            if (n > 0) {
                                firstReplyChunk.write(tmp, 0, n)
                                pickedDirect = directS
                                directWon.set(true)
                            }
                            else if (n < 0) { try { directS.close() } catch (_: Exception) {} }
                        } catch (_: java.net.SocketTimeoutException) {}
                          catch (_: Exception) { try { directS.close() } catch (_: Exception) {} }
                    }
                    if (pickedDirect != null) break
                    // VPN читаем только если Hello туда ушёл (после 800мс holdoff)
                    if (vpnS != null && pickedVpn == null && !vpnS.isClosed && vpnHelloSent.get()) {
                        try {
                            val n = vpnS.getInputStream().read(tmp)
                            if (n > 0) { firstReplyChunk.write(tmp, 0, n); pickedVpn = vpnS }
                            else if (n < 0) { try { vpnS.close() } catch (_: Exception) {} }
                        } catch (_: java.net.SocketTimeoutException) {}
                          catch (_: Exception) { try { vpnS.close() } catch (_: Exception) {} }
                    }
                    if (pickedVpn != null) break
                    // Если оба сокета закрылись — выходим
                    val directDead = directS == null || directS.isClosed
                    val vpnDead = vpnS == null || vpnS.isClosed
                    if (directDead && vpnDead) break
                }
                when {
                    pickedDirect != null -> { winner = pickedDirect; viaVpn = false }
                    pickedVpn != null -> { winner = pickedVpn; viaVpn = true }
                    else -> {
                        Log.w(TAG, "race TIMEOUT $host:$port — no path replied")
                        try { directS?.close() } catch (_: Exception) {}
                        try { vpnS?.close() } catch (_: Exception) {}
                        try { directSendThread?.interrupt() } catch (_: Exception) {}
                        try { vpnSendThread?.interrupt() } catch (_: Exception) {}
                        if (usedStrategy > 0) TlsFragmenter.reportStrategyFailure(usedStrategy)
                        if (directS != null) {
                            recordDirectFailure(host)
                            recordDirectOutcome(false)
                        }
                        return
                    }
                }
            }
            // Закрываем проигравший путь
            if (viaVpn) {
                try { directS?.close() } catch (_: Exception) {}
                try { directSendThread?.interrupt() } catch (_: Exception) {}
            } else {
                try { vpnS?.close() } catch (_: Exception) {}
                try { vpnSendThread?.interrupt() } catch (_: Exception) {}
            }
            try { winner.soTimeout = 30000 } catch (_: Exception) {}
            Log.i(TAG, "race WON $host:$port via=${if (viaVpn) "VPN" else "direct"} firstChunk=${firstReplyChunk.size()}b")

            // 8. Bridge: winner ↔ client.
            val uin = winner.getInputStream()
            val uout = winner.getOutputStream()

            // Сначала пересылаем уже принятый firstReplyChunk клиенту
            if (firstReplyChunk.size() > 0) {
                try { cout.write(firstReplyChunk.toByteArray()); cout.flush() } catch (_: Exception) {}
            }

            // client -> winner: остальной трафик (Client Hello уже ушёл).
            // Для direct-пути фрагментируем следующие 1-2 record'а (CCS/Finished).
            val upJob = scope.launch {
                try {
                    val buf = ByteArray(16384)
                    var earlyRecordsToSplit = if (viaVpn) 0 else 2
                    while (isActive) {
                        val n = cin.read(buf)
                        if (n <= 0) break
                        if (earlyRecordsToSplit > 0 && n > 6
                            && (buf[0] == 0x14.toByte() || buf[0] == 0x16.toByte())) {
                            uout.write(buf, 0, 1); uout.flush()
                            Thread.sleep(2)
                            uout.write(buf, 1, n - 1); uout.flush()
                            earlyRecordsToSplit--
                        } else {
                            uout.write(buf, 0, n); uout.flush()
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    try { winner.shutdownOutput() } catch (_: Exception) {}
                }
            }

            // winner -> client.
            var bytesDown = firstReplyChunk.size().toLong()
            var reportedSuccess = false
            try {
                val buf = ByteArray(16384)
                while (true) {
                    val n = uin.read(buf)
                    if (n <= 0) break
                    cout.write(buf, 0, n); cout.flush()
                    bytesDown += n
                    if (!viaVpn) firstReplyChunkSize.set(bytesDown.toInt().coerceAtMost(Int.MAX_VALUE))
                    if (!reportedSuccess && !viaVpn && usedStrategy > 0) {
                        val sinceStart = System.currentTimeMillis() - connectStart
                        if (bytesDown > 32768 || (bytesDown > 8192 && sinceStart > 10000)) {
                            TlsFragmenter.reportStrategySuccess(usedStrategy)
                            reportedSuccess = true
                        }
                    }
                }
            } catch (_: Exception) {}

            val durationMs = System.currentTimeMillis() - connectStart
            val realSuccess = bytesDown > 32768 || (bytesDown > 8192 && durationMs > 10000)
            val viaTag = if (viaVpn) "VPN" else "direct"
            if (realSuccess) {
                Log.i(TAG, "DONE host=$host via=$viaTag strategy=$usedStrategy bytes=$bytesDown dur=${durationMs}ms ✓")
                if (!viaVpn) {
                    recordDirectSuccess(host)
                    recordDirectOutcome(true)
                } else {
                    recordVpnOutcome(true)
                }
            } else if (durationMs > 4000) {
                Log.w(TAG, "TIMEOUT host=$host via=$viaTag strategy=$usedStrategy bytes=$bytesDown dur=${durationMs}ms ✗")
                if (!viaVpn) {
                    if (usedStrategy > 0) TlsFragmenter.reportStrategyFailure(usedStrategy)
                    recordDirectFailure(host)
                    recordDirectOutcome(false)
                } else {
                    recordVpnOutcome(false)
                }
            }

            upJob.cancel()
            try { winner.close() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.d(TAG, "client error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /** Открывает оба сокета параллельно, возвращает оба готовых к использованию.
     *  Реальный race происходит ПОСЛЕ отправки Client Hello — побеждает тот кто
     *  первым ответил данными от сервера (Server Hello). */
    private data class RacePair(val direct: Socket?, val vpn: Socket?)

    private fun openBothPaths(host: String, port: Int): RacePair {
        val bridgePort = vpnBridgePort

        // STORM-режим: DPI рубит >85% коннектов. Direct не открываем,
        // экономим conntrack-слоты, гоним только VPN (если VPN жив).
        if (bridgePort != null && isStorm() && !isVpnDisabled()) {
            return try {
                val s = openVpnBridgeSocket(host, port, bridgePort, delayMs = 0)
                RacePair(null, s)
            } catch (_: Exception) { RacePair(null, null) }
        }

        // VPN сам мёртв — гоним только direct. НО если ещё и STORM активен
        // (direct тоже плохой), включаем оба пути — лучше что-то чем ничего.
        val effectiveBridgePort = if (isVpnDisabled() && !isStorm()) null else bridgePort

        val directTask = java.util.concurrent.FutureTask<Socket> {
            val s = Socket()
            s.bind(InetSocketAddress(0))
            try { vpnService?.protect(s) } catch (_: Exception) {}
            s.connect(InetSocketAddress(host, port), 8000)
            s.tcpNoDelay = true
            s
        }
        Thread(directTask, "race-direct").start()

        if (effectiveBridgePort == null) {
            return try { RacePair(directTask.get(), null) }
            catch (_: Exception) { RacePair(null, null) }
        }

        val vpnTask = java.util.concurrent.FutureTask<Socket> {
            openVpnBridgeSocket(host, port, effectiveBridgePort, delayMs = 250)
        }
        Thread(vpnTask, "race-vpn").start()

        var d: Socket? = null
        var v: Socket? = null
        try { d = directTask.get(10, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        try { v = vpnTask.get(10, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
        return RacePair(d, v)
    }

    private fun openVpnBridgeSocket(host: String, port: Int, bridgePort: Int, delayMs: Long): Socket {
        if (delayMs > 0) Thread.sleep(delayMs)
        val s = Socket()
        s.connect(InetSocketAddress("127.0.0.1", bridgePort), 5000)
        s.tcpNoDelay = true
        val out = s.getOutputStream()
        val ins = java.io.DataInputStream(s.getInputStream())
        out.write(byteArrayOf(0x05, 0x01, 0x00)); out.flush()
        if (ins.read() != 0x05 || ins.read() != 0x00) throw IOException("bridge greeting")
        val hostBytes = host.toByteArray(Charsets.US_ASCII)
        out.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()))
        out.write(hostBytes)
        out.write(byteArrayOf((port shr 8).toByte(), port.toByte()))
        out.flush()
        if (ins.read() != 0x05) throw IOException("bridge bad ver")
        val rep = ins.read()
        if (rep != 0x00) throw IOException("bridge rep=$rep")
        ins.read()
        val atyp = ins.read()
        val addrLen = when (atyp) {
            0x01 -> 4; 0x04 -> 16
            0x03 -> ins.read()
            else -> throw IOException("bad atyp")
        }
        ins.readFully(ByteArray(addrLen + 2))
        return s
    }

    /** Проверяем что собрали весь TLS record по полю length в header */
    private fun isTlsRecordComplete(buf: ByteArray): Boolean {
        if (buf.size < 5) return false
        if (buf[0] != 0x16.toByte()) return true  // не TLS — не ждём
        val recLen = ((buf[3].toInt() and 0xFF) shl 8) or (buf[4].toInt() and 0xFF)
        return buf.size >= 5 + recLen
    }

    private fun reply(out: java.io.OutputStream, code: Int) {
        try {
            out.write(byteArrayOf(0x05, code.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            out.flush()
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "DpiSocks"
    }
}
