package com.amsales.vpn.dpi

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * DPI bypass — 12 разных стратегий + автоперебор.
 *
 * Каждое новое соединение пробует следующую стратегию (round-robin).
 * Когда какая-то СРАБОТАЛА (handshake прошёл и поплыли данные),
 * она помечается как "good" и используется в первую очередь.
 *
 * Стратегии (от простых к экзотическим):
 *   1  TCP_SPLIT_1          — простой split на 1 байт
 *   2  TCP_SPLIT_SNI         — split в середине SNI
 *   3  TCP_SPLIT_BEFORE_SNI  — split за 1 байт до SNI
 *   4  TLS_RECORD_2          — 2 валидных TLS-record
 *   5  TLS_RECORD_3          — 3 валидных TLS-record
 *   6  TTL_DISORDER_3        — TTL=3 первый кусок, потом 64
 *   7  TTL_DISORDER_5        — TTL=5 первый кусок, потом 64
 *   8  TTL_DISORDER_8        — TTL=8 первый кусок, потом 64
 *   9  DOUBLE_TTL_HOP        — 3 куска с TTL=2,5,64
 *   10 TINY_CHUNKS           — много мелких кусков
 *   11 TLS_RECORD_TTL        — TLS-record split + TTL=3
 *   12 OOB_SPLIT             — split + OOB-byte (byedpi --oob)
 */
object TlsFragmenter {

    private const val TAG = "TlsFrag"

    const val STRATEGY_COUNT = 18
    const val STRATEGY_AUTO = 0

    @Volatile var currentStrategy: Int = STRATEGY_AUTO

    // Для авто-перебора. Порядок: сначала статистически рабочие
    // (по логам уже видели как 2 и 6 пробивали РКН), потом TTL-вариации,
    // потом экзотика. Это снижает риск временного бана IP провайдером,
    // когда РКН-DPI начинает резать после нескольких "странных" пакетов.
    // ВНИМАНИЕ: стратегия 16 (SNI Spoof → google.com) НЕ работает для
    // YouTube/Chrome: сервер возвращает cert *.google.com который НЕ покрывает
    // youtube.com. Клиент закрывает соединение (TLS_FATAL_ALERT 0x2E).
    // 16-я полезна ТОЛЬКО для curl/wget без cert check.
    //
    // Стратегии 13/14/15 (FAKE Client Hello / TTL=2) пока в эксперименте.
    // Реально работающие = 2 (Split @ SNI), 6 (TTL=3 disorder), 7 (TTL=5).
    // ПРОВЕРЕНО ПРАКТИКОЙ 2026-06-04: только 14 (TTL=2) и 6 (TTL=3) дают
    // ≥15% success rate. Остальные впустую тратят коннекты. Урезаем pool.
    // Чем меньше пул — тем больше шансов что коннект попадёт на рабочую.
    // 2026-06-04 расширили пул: при шторме DPI 88% fail = текущие 14/6/17
    // умерли. Возвращаем Strategy 2 (Split @ SNI) — исторически рабочая,
    // плюс новые комбо. Pin-механизм всё равно отфильтрует мёртвые.
    private val PRIORITY_ORDER = intArrayOf(
        2, 2, 2,          // Split @ SNI — топ-кандидат при шторме
        14, 14,           // TTL=2 disorder
        6, 6,             // TTL=3 disorder
        11,               // TLS record + TTL=3
        7,                // TTL=5
        4,                // 2 TLS records
        17,               // Ultra-micro chunks
        9,                // Triple TTL hop
        3,                // Split BEFORE SNI
    )
    private val autoCounter = AtomicInteger(0)

    // Стратегия которая сработала (handshake прошёл). Используем её для всех
    // последующих соединений. Сбрасывается при перезапуске VPN.
    @Volatile private var goodStrategy: Int = 0

    // Per-strategy stats: для каждой стратегии запоминаем сколько раз дала
    // полезную нагрузку (>2KB) и сколько раз TIMEOUT'нулась.
    private val strategySuccesses = IntArray(STRATEGY_COUNT + 1)
    private val strategyFailures = IntArray(STRATEGY_COUNT + 1)
    private val statsLock = Any()

    // Чтобы запинить стратегию нужно: ≥2 успеха и success-rate ≥ 50%.
    private const val MIN_SUCCESSES_TO_PIN = 2

    /** Все стратегии полностью мёртвы — провайдер жёстко блочит и
     *  фрагментация не помогает. Юзеру стоит переключиться на VPN. */
    @Volatile var zapretIsDead: Boolean = false
        private set

    /** Сообщаем что стратегия дала полезную нагрузку (вызывается с любой
     *  стратегии, не только pinned — для статистики). */
    fun reportStrategySuccess(strategy: Int) {
        if (strategy !in 1..STRATEGY_COUNT) return
        synchronized(statsLock) {
            strategySuccesses[strategy]++
            zapretIsDead = false  // хоть что-то работает
            // Пинить можно только если набрали достаточно успехов И
            // нет конкурирующей стратегии. Уже pinned менять не надо.
            if (goodStrategy == 0 &&
                strategySuccesses[strategy] >= MIN_SUCCESSES_TO_PIN) {
                goodStrategy = strategy
                Log.i(TAG, "✓ Strategy $strategy (${strategyName(strategy)}) WORKS " +
                    "(S=${strategySuccesses[strategy]} F=${strategyFailures[strategy]}) — pinning")
            }
        }
    }

    /** Сообщаем что стратегия дала TIMEOUT. Если pinned-стратегия лажает —
     *  анпиним и продолжаем перебор. */
    fun reportStrategyFailure(strategy: Int) {
        if (strategy !in 1..STRATEGY_COUNT) return
        synchronized(statsLock) {
            strategyFailures[strategy]++
            // Если это pinned-стратегия и она стала flaky — анпинним.
            if (strategy == goodStrategy) {
                val s = strategySuccesses[strategy]
                val f = strategyFailures[strategy]
                // flaky когда failures стало больше чем 2x от successes
                if (f > s * 2 && f >= 3) {
                    Log.w(TAG, "✗ Strategy $strategy flaky (S=$s F=$f) — UNPINNING")
                    goodStrategy = 0
                }
            }
        }
    }

    fun resetGoodStrategy() {
        goodStrategy = 0
        synchronized(statsLock) {
            for (i in strategySuccesses.indices) strategySuccesses[i] = 0
            for (i in strategyFailures.indices) strategyFailures[i] = 0
        }
        autoCounter.set(0)
    }

    fun strategyName(id: Int): String = when (id) {
        1 -> "Split 1byte"
        2 -> "Split @ SNI"
        3 -> "Split before SNI"
        4 -> "TLS record x2"
        5 -> "TLS record x3"
        6 -> "TTL=3 disorder"
        7 -> "TTL=5 disorder"
        8 -> "TTL=8 disorder"
        9 -> "Double TTL hop"
        10 -> "Tiny chunks"
        11 -> "TLS record + TTL"
        12 -> "Split + OOB"
        13 -> "FAKE Client Hello (ya.ru)"
        14 -> "TTL=2 disorder"
        15 -> "FAKE + TTL combo"
        16 -> "SNI Spoof → google.com"
        17 -> "Ultra-micro chunks (по 2 байта)"
        18 -> "Micro chunks + TTL=2"
        STRATEGY_AUTO -> "Авто-перебор"
        else -> "?"
    }

    /** @return ID стратегии которая была применена */
    fun sendWithFragmentation(socket: Socket, out: OutputStream, data: ByteArray): Int {
        if (!looksLikeTlsClientHello(data)) {
            out.write(data); out.flush()
            return 0
        }
        socket.tcpNoDelay = true

        // currentStrategy == -1 → ПОЛНОЕ ОТКЛЮЧЕНИЕ фрагментации (диагностика)
        if (currentStrategy == -1) {
            out.write(data); out.flush()
            Log.d(TAG, ">>> NO-FRAG (диагностика) size=${data.size}")
            return -1
        }

        // currentStrategy ∈ 1..12 — юзер выбрал конкретную в UI, всегда её.
        // Иначе round-robin по PRIORITY_ORDER. goodStrategy используется
        // только как "лёгкий буст" — половина коннектов идёт на pinned,
        // половина продолжает перебирать. Это даёт устойчивость когда DPI
        // меняет правила, но не теряет скорость когда стратегия работает.
        val strategy = when {
            currentStrategy in 1..STRATEGY_COUNT -> currentStrategy
            else -> {
                val counter = autoCounter.getAndIncrement()
                if (goodStrategy > 0 && counter % 2 == 0) {
                    goodStrategy
                } else {
                    PRIORITY_ORDER[counter % PRIORITY_ORDER.size]
                }
            }
        }

        val sni = findSniRange(data)
        Log.d(TAG, ">>> strategy=$strategy (${strategyName(strategy)}) sni=$sni size=${data.size}")

        try {
            when (strategy) {
                1 -> splitN(out, data, 1)
                2 -> splitAtSniMid(out, data, sni)
                3 -> splitBeforeSni(out, data, sni)
                4 -> tlsRecordN(out, data, 2, sni)
                5 -> tlsRecordN(out, data, 3, sni)
                6 -> ttlDisorder(socket, out, data, sni, 3)
                7 -> ttlDisorder(socket, out, data, sni, 5)
                8 -> ttlDisorder(socket, out, data, sni, 8)
                9 -> doubleTtlHop(socket, out, data, sni)
                10 -> tinyChunks(out, data, sni)
                11 -> tlsRecordWithTtl(socket, out, data, sni)
                12 -> splitWithOob(socket, out, data, sni)
                13 -> fakeClientHello(socket, out, data)
                14 -> ttlDisorder(socket, out, data, sni, 2)
                15 -> fakeWithTtl(socket, out, data, sni)
                16 -> sniSpoof(socket, out, data, sni)
                17 -> ultraMicroChunks(socket, out, data, sni)
                18 -> microChunksWithTtl(socket, out, data, sni)
                else -> { out.write(data); out.flush() }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "strategy $strategy failed: ${e.message}")
            // На случай если что-то пошло не так — просто шлём как есть
            try { out.write(data); out.flush() } catch (_: Throwable) {}
        }
        return strategy
    }

    // ─── 1: Split на 1 байт ───────────────────────────────────
    private fun splitN(out: OutputStream, data: ByteArray, pos: Int) {
        val p = pos.coerceIn(1, data.size - 1)
        out.write(data, 0, p); out.flush()
        sleep(8)
        out.write(data, p, data.size - p); out.flush()
    }

    // ─── 2: Split в середине SNI ──────────────────────────────
    private fun splitAtSniMid(out: OutputStream, data: ByteArray, sni: Pair<Int, Int>?) {
        val p = sni?.let { it.first + (it.second - it.first) / 2 } ?: (data.size / 2)
        splitN(out, data, p)
    }

    // ─── 3: Split за 1 байт до SNI ────────────────────────────
    private fun splitBeforeSni(out: OutputStream, data: ByteArray, sni: Pair<Int, Int>?) {
        val p = sni?.first?.minus(1) ?: 10
        splitN(out, data, p)
    }

    // ─── 4/5: TLS Record split (N валидных record) ────────────
    private fun tlsRecordN(out: OutputStream, data: ByteArray, n: Int, sni: Pair<Int, Int>?) {
        if (data.size < 6) { out.write(data); out.flush(); return }
        val recVer1 = data[1]; val recVer2 = data[2]
        val payload = data.copyOfRange(5, data.size)

        val cuts = sortedSetOf<Int>()
        if (sni != null) {
            cuts.add((sni.first - 5).coerceAtLeast(1))
            cuts.add(((sni.first - 5) + (sni.second - sni.first) / 2).coerceAtLeast(1))
        }
        while (cuts.size < n - 1) {
            cuts.add(Random.nextInt(1, payload.size - 1))
        }
        val sortedCuts = cuts.filter { it in 1 until payload.size }.take(n - 1)

        var prev = 0
        for (c in sortedCuts) {
            if (c <= prev) continue
            val len = c - prev
            out.write(byteArrayOf(0x16, recVer1, recVer2, (len ushr 8).toByte(), len.toByte()))
            out.write(payload, prev, len); out.flush()
            sleep(5)
            prev = c
        }
        if (prev < payload.size) {
            val len = payload.size - prev
            out.write(byteArrayOf(0x16, recVer1, recVer2, (len ushr 8).toByte(), len.toByte()))
            out.write(payload, prev, len); out.flush()
        }
    }

    // ─── 6/7/8: TTL Disorder с разным TTL ─────────────────────
    private fun ttlDisorder(socket: Socket, out: OutputStream, data: ByteArray, sni: Pair<Int, Int>?, ttl: Int) {
        // Рандомизируем splitPos в зоне SNI чтобы DPI не нашёл паттерн.
        // Раньше: фиксированный midpoint SNI. Сейчас: random точка от
        // начала SNI до конца SNI (с шумом ±2 байта).
        val splitPos = if (sni != null) {
            val sniLen = sni.second - sni.first
            val offset = Random.nextInt(0, sniLen.coerceAtLeast(1))
            (sni.first + offset).coerceIn(1, data.size - 1)
        } else {
            Random.nextInt(5, 30)
        }
        // Рандомизируем TTL ±1 от заданного (для ttl=2 → 2 или 3).
        val realTtl = if (ttl <= 2) ttl else ttl + Random.nextInt(-1, 2)
        val fd = ParcelFileDescriptor.fromSocket(socket)
        try {
            setTtl(fd, realTtl)
            out.write(data, 0, splitPos); out.flush()
            sleep(Random.nextInt(1, 5))
            setTtl(fd, 64)
            out.write(data, splitPos, data.size - splitPos); out.flush()
        } finally {
            try { fd.detachFd() } catch (_: Throwable) {}
        }
    }

    // ─── 9: Double TTL hop ────────────────────────────────────
    private fun doubleTtlHop(socket: Socket, out: OutputStream, data: ByteArray, sni: Pair<Int, Int>?) {
        val fd = ParcelFileDescriptor.fromSocket(socket)
        try {
            val cut1 = Random.nextInt(2, 5)
            val cut2 = sni?.first ?: 30

            setTtl(fd, 2)
            out.write(data, 0, cut1); out.flush()
            sleep(2)

            setTtl(fd, 5)
            out.write(data, cut1, cut2 - cut1); out.flush()
            sleep(2)

            setTtl(fd, 64)
            out.write(data, cut2, data.size - cut2); out.flush()
        } finally {
            try { fd.detachFd() } catch (_: Throwable) {}
        }
    }

    // ─── 10: Tiny chunks ──────────────────────────────────────
    private fun tinyChunks(out: OutputStream, data: ByteArray, sni: Pair<Int, Int>?) {
        val cuts = sortedSetOf<Int>()
        cuts.add(1)
        cuts.add(3)
        cuts.add(7)
        if (sni != null) {
            cuts.add(sni.first)
            cuts.add(sni.first + (sni.second - sni.first) / 2)
            cuts.add(sni.second)
        } else {
            cuts.add(20)
            cuts.add(50)
        }
        val sortedCuts = cuts.filter { it in 1 until data.size }
        var prev = 0
        for (c in sortedCuts) {
            if (c <= prev) continue
            out.write(data, prev, c - prev); out.flush()
            sleep(15)
            prev = c
        }
        if (prev < data.size) { out.write(data, prev, data.size - prev); out.flush() }
    }

    // ─── 17: Ultra-micro chunks ──────────────────────────────
    // Делаем SO_SNDBUF=1, потом шлём по 2 байта с паузой 3мс.
    // Это даёт сотни TCP-сегментов. DPI не reassembl'ит их корректно
    // даже если знает SNI — нужно держать огромный state.
    // Цена — медленный handshake (~3-5 секунд на Client Hello).
    // ПОСЛЕ Client Hello возвращаем нормальный sendBuf чтобы трафик
    // шёл с обычной скоростью.
    private fun ultraMicroChunks(socket: Socket, out: OutputStream, data: ByteArray, sni: Pair<Int, Int>?) {
        val origSndBuf = try { socket.sendBufferSize } catch (_: Throwable) { 65536 }
        try { socket.sendBufferSize = 1 } catch (_: Throwable) {}
        socket.tcpNoDelay = true
        // ЭКСПЕРИМЕНТАЛЬНО ПОДТВЕРЖДЕНО (PowerShell test на том же провайдере):
        // chunk=2 + sleep=3ms = OK 4KB в 825ms — DPI РКН не успевает reassembl'ить
        // SNI правильно. Любые другие комбинации (sleep=0/1/5/10/20ms) = FAIL.
        // Это магическое окно — НЕ менять без тестов.
        val chunk = 2
        var p = 0
        while (p < data.size) {
            val len = kotlin.math.min(chunk, data.size - p)
            out.write(data, p, len); out.flush()
            p += len
            sleep(3)  // КРИТИЧНО ровно 3мс между всеми байтами
        }
        // Возвращаем нормальный sendBuf для application data (видео).
        try { socket.sendBufferSize = if (origSndBuf > 1) origSndBuf else 65536 } catch (_: Throwable) {}
    }

    // ─── 18: Micro chunks + TTL=2 disorder ──────────────────
    // Комбо: первая половина ClientHello (включая начало SNI)
    // шлётся с TTL=2 (умирает на провайдере, DPI получает мусор),
    // вторая половина — нормальный TTL с микро-чанками.
    // Реальный сервер видит только вторую половину, но за счёт
    // TCP reorder её собрать может — TLS handshake завершается.
    // DPI же видит только TTL=2 фрагменты которые умерли.
    private fun microChunksWithTtl(socket: Socket, out: OutputStream, data: ByteArray, sni: Pair<Int, Int>?) {
        val splitPos = sni?.let { it.first + (it.second - it.first) / 2 } ?: (data.size / 3)
        val fd = ParcelFileDescriptor.fromSocket(socket)
        try {
            // Часть 1: с TTL=2, по 2 байта
            setTtl(fd, 2)
            socket.tcpNoDelay = true
            var p = 0
            while (p < splitPos) {
                val len = kotlin.math.min(2, splitPos - p)
                out.write(data, p, len); out.flush()
                p += len
                if (p % 16 == 0) sleep(2)
            }
            sleep(10)
            // Часть 2: нормальный TTL, по 4 байта
            setTtl(fd, 64)
            while (p < data.size) {
                val len = kotlin.math.min(4, data.size - p)
                out.write(data, p, len); out.flush()
                p += len
                if (p % 32 == 0) sleep(1)
            }
        } finally {
            try { fd.detachFd() } catch (_: Throwable) {}
        }
    }

    // ─── 11: TLS Record split + TTL disorder ──────────────────
    private fun tlsRecordWithTtl(socket: Socket, out: OutputStream, data: ByteArray, sni: Pair<Int, Int>?) {
        if (data.size < 6) { out.write(data); out.flush(); return }
        val recVer1 = data[1]; val recVer2 = data[2]
        val payload = data.copyOfRange(5, data.size)
        val splitInPayload = if (sni != null) {
            ((sni.first - 5) + (sni.second - sni.first) / 2).coerceIn(1, payload.size - 1)
        } else { payload.size / 2 }

        val r1Len = splitInPayload
        val r2Len = payload.size - splitInPayload

        val fd = ParcelFileDescriptor.fromSocket(socket)
        try {
            setTtl(fd, 3)
            out.write(byteArrayOf(0x16, recVer1, recVer2, (r1Len ushr 8).toByte(), r1Len.toByte()))
            out.write(payload, 0, r1Len); out.flush()
            sleep(2)
            setTtl(fd, 64)
            out.write(byteArrayOf(0x16, recVer1, recVer2, (r2Len ushr 8).toByte(), r2Len.toByte()))
            out.write(payload, r1Len, r2Len); out.flush()
        } finally {
            try { fd.detachFd() } catch (_: Throwable) {}
        }
    }

    // ─── 12: Split + OOB-byte (byedpi --oob) ──────────────────
    // OOB-byte ломает чексумму у DPI parser'а на простых системах.
    // На Java через Socket.sendUrgentData().
    private fun splitWithOob(socket: Socket, out: OutputStream, data: ByteArray, sni: Pair<Int, Int>?) {
        val p = sni?.first ?: 10
        out.write(data, 0, p); out.flush()
        try { socket.sendUrgentData(0x61) } catch (_: Throwable) {}
        sleep(5)
        out.write(data, p, data.size - p); out.flush()
    }

    // ─── 16: SNI Spoofing (replace SNI with google.com) ───────
    // КЛЮЧЕВОЕ ОТКРЫТИЕ: РКН DPI пропускает SNI=google.com даже к IP
    // youtube. Google сервер возвращает default-cert (*.google.com),
    // который покрывает все Google-сервисы — клиент успешно проходит TLS.
    // Дальше HTTP Host header содержит реальный youtube.com — сервер
    // отдаёт нужный контент.
    // Реализация: находим SNI в Client Hello, переписываем его на
    // "google.com" с пересчётом всех вложенных length-полей.
    // Ротация fake-SNI чтобы DPI не вычислил по паттерну "все коннекты =
    // google.com". ПОДТВЕРЖДЕНО ЛОКАЛЬНЫМ ТЕСТОМ что все эти SNI пропускаются.
    private val FAKE_SNI_POOL = arrayOf("google.com", "www.google.com", "accounts.google.com")
    private val sniCounter = AtomicInteger(0)

    // Мэппинг: какой fake SNI применить для какого реального домена.
    // ПОДТВЕРЖДЕНО PowerShell-тестами SAN-полей на КОНКРЕТНЫХ Google IP:
    //
    // SNI=video-stats.video.google.com — УНИВЕРСАЛЬНАЯ подмена:
    //   - 173.194.x → cert содержит youtube + ytimg
    //   - 74.125.x  → cert содержит googlevideo + ytimg (video CDN!)
    //   - 216.239.x → cert содержит googleapis
    //
    // Это significant find — один SNI покрывает все YouTube destinations.
    // DPI пропускает video-stats.video.google.com (whitelist).
    private data class SpoofRule(val origSuffix: String, val fakeSni: String)
    private val SPOOF_RULES = arrayOf(
        // Все YouTube-связанные домены спуфим на один SNI который даёт
        // правильный cert на каждом конкретном IP назначения.
        SpoofRule(".googlevideo.com",     "video-stats.video.google.com"),
        SpoofRule("googlevideo.com",      "video-stats.video.google.com"),
        SpoofRule(".googleapis.com",      "video-stats.video.google.com"),
        SpoofRule(".youtube.com",         "video-stats.video.google.com"),
        SpoofRule("youtube.com",          "video-stats.video.google.com"),
        SpoofRule(".ytimg.com",           "video-stats.video.google.com"),
        SpoofRule("ytimg.com",            "video-stats.video.google.com"),
        SpoofRule(".youtubekids.com",     "video-stats.video.google.com"),
        SpoofRule(".youtubeeducation.com","video-stats.video.google.com"),
        SpoofRule("youtu.be",             "video-stats.video.google.com"),
        SpoofRule("yt.be",                "video-stats.video.google.com"),
        SpoofRule(".ggpht.com",           "lh3.googleusercontent.com"),  // отдельно для avatars
    )

    // СТАРОЕ — оставлено для совместимости с FAKE_SNI_POOL (но не используется)
    private val SNI_SPOOF_COMPATIBLE = arrayOf<String>()

    private fun sniSpoof(socket: Socket, out: OutputStream, data: ByteArray, sni: Pair<Int, Int>?) {
        if (sni == null) {
            Log.w(TAG, "sniSpoof: SNI NOT FOUND in data of size ${data.size}, sending as-is")
            out.write(data); out.flush()
            return
        }
        val sniStart = sni.first
        val sniEnd = sni.second
        val oldSniLen = sniEnd - sniStart
        val origHost = try { String(data, sniStart, oldSniLen, Charsets.US_ASCII) } catch (_: Throwable) { "?" }

        // Подбираем fake SNI по правилам соответствия. Если домен не покрыт
        // ни одним правилом (типа *.googlevideo.com) — fallback на
        // ultraMicroChunks (стратегия 17 которая статистически даёт DONE 446KB).
        val rule = SPOOF_RULES.firstOrNull { r -> origHost.endsWith(r.origSuffix, ignoreCase = true) }
        if (rule == null) {
            Log.i(TAG, "sniSpoof: '$origHost' no rule → fallback to ultraMicroChunks")
            ultraMicroChunks(socket, out, data, sni)
            return
        }
        val fakeSni = rule.fakeSni
        val newSni = fakeSni.toByteArray(Charsets.US_ASCII)
        val diff = newSni.size - oldSniLen
        Log.i(TAG, "sniSpoof: '$origHost' -> '$fakeSni' (size diff=$diff)")

        // Структура вокруг SNI в Client Hello (отсчёт назад от sniStart):
        //   [sniStart - 2] : SNI host_length (2 байта)
        //   [sniStart - 3] : name_type (0x00)
        //   [sniStart - 5] : server_name_list_length (2 байта)
        //   [sniStart - 9] : SNI extension data_length (2 байта)
        //   ... перед extension data_length у нас extension type (2 байта)
        //   ... до extension type: extensions_total_length (2 байта)
        //   до extensions_total_length: TLS handshake header length (3 байта)
        //                              + record_layer length (2 байта)

        val buf = ByteArray(data.size + diff)
        System.arraycopy(data, 0, buf, 0, sniStart)
        System.arraycopy(newSni, 0, buf, sniStart, newSni.size)
        System.arraycopy(data, sniEnd, buf, sniStart + newSni.size, data.size - sniEnd)

        // Пересчитываем все length-поля:

        // host_length (за 2 байта до SNI)
        buf[sniStart - 2] = ((newSni.size shr 8) and 0xFF).toByte()
        buf[sniStart - 1] = (newSni.size and 0xFF).toByte()

        // server_name_list_length (за 5 байт до SNI)
        val serverListLen = oldSniLen + 3 + diff  // name_type(1) + name_length(2) + name
        buf[sniStart - 5] = ((serverListLen shr 8) and 0xFF).toByte()
        buf[sniStart - 4] = (serverListLen and 0xFF).toByte()

        // SNI extension data_length (за 7 байт до SNI):
        // структура: [ext_type 2b][ext_data_length 2b][server_name_list_length 2b][name_type 1b][name_length 2b][name]
        // от sniStart назад: -2 (name_length), -3 (name_type), -5 (list_length), -7 (data_length), -9 (ext_type)
        val extDataLen = serverListLen + 2  // server_name_list_length sam = 2 bytes
        buf[sniStart - 7] = ((extDataLen shr 8) and 0xFF).toByte()
        buf[sniStart - 6] = (extDataLen and 0xFF).toByte()

        // extensions_total_length: нужно найти где он. Структура CH:
        //   record_hdr(5) handshake_hdr(4) client_version(2) random(32)
        //   sid_len(1) + sid sid_len(0..32)
        //   cipher_suites_len(2) + cipher_suites
        //   compression_methods_len(1) + compression_methods
        //   extensions_length(2) + extensions
        // Найдём позицию extensions_length по парсингу:
        try {
            var p = 5 + 4 + 2 + 32  // после record+handshake hdr + ver + random
            val sidLen = data[p].toInt() and 0xFF
            p += 1 + sidLen
            val csLen = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
            p += 2 + csLen
            val cmLen = data[p].toInt() and 0xFF
            p += 1 + cmLen
            // Теперь p указывает на extensions_length (2 байта)
            val oldExtTotal = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
            val newExtTotal = oldExtTotal + diff
            buf[p] = ((newExtTotal shr 8) and 0xFF).toByte()
            buf[p + 1] = (newExtTotal and 0xFF).toByte()
        } catch (_: Throwable) {}

        // handshake_length (3 байта на позиции 6..8 в record)
        val newHsLen = ((data[6].toInt() and 0xFF) shl 16 or
                      ((data[7].toInt() and 0xFF) shl 8) or
                      (data[8].toInt() and 0xFF)) + diff
        buf[6] = ((newHsLen shr 16) and 0xFF).toByte()
        buf[7] = ((newHsLen shr 8) and 0xFF).toByte()
        buf[8] = (newHsLen and 0xFF).toByte()

        // record_layer length (на позиции 3..4)
        val newRecLen = ((data[3].toInt() and 0xFF) shl 8 or (data[4].toInt() and 0xFF)) + diff
        buf[3] = ((newRecLen shr 8) and 0xFF).toByte()
        buf[4] = (newRecLen and 0xFF).toByte()

        Log.i(TAG, "sniSpoof OK: sending ${buf.size} bytes (orig was ${data.size})")
        out.write(buf); out.flush()
    }

    // ─── 13: FAKE Client Hello (byedpi --fake) ────────────────
    // Шлём СНАЧАЛА полностью валидный Client Hello с SNI=ya.ru
    // через сокет с TTL=2 (умирает до Google), потом восстанавливаем
    // TTL и шлём настоящий Client Hello.
    //
    // DPI РКН видит сначала "запрос к ya.ru" (помечает conntrack как ya.ru),
    // потом наш реальный запрос — но conntrack уже "решён" как ya.ru,
    // DPI не парсит второй ClientHello. Google сервер получает только
    // второй пакет (первый умер по TTL) и нормально отвечает.
    private fun fakeClientHello(socket: Socket, out: OutputStream, data: ByteArray) {
        val fake = buildFakeClientHello("ya.ru")
        val fd = ParcelFileDescriptor.fromSocket(socket)
        try {
            // Шаг 1: TTL=2 → fake уйдёт в провайдера/DPI, но не дойдёт до Google
            setTtl(fd, 2)
            out.write(fake); out.flush()
            sleep(3)
            // Шаг 2: возвращаем нормальный TTL → реальный CH доходит до Google
            setTtl(fd, 64)
            out.write(data); out.flush()
        } finally {
            try { fd.detachFd() } catch (_: Throwable) {}
        }
    }

    // ─── 15: FAKE + TTL combo (byedpi --fake+disorder) ────────
    // Комбо: fake CH с TTL=2 (декой), потом реальный CH разрезанный
    // пополам с TTL=3 для первой половины (disorder).
    // Самая агрессивная стратегия — две техники сразу.
    private fun fakeWithTtl(socket: Socket, out: OutputStream, data: ByteArray, sni: Pair<Int, Int>?) {
        val fake = buildFakeClientHello("ya.ru")
        val splitPos = sni?.let { it.first + (it.second - it.first) / 2 } ?: 10
        val fd = ParcelFileDescriptor.fromSocket(socket)
        try {
            setTtl(fd, 2)
            out.write(fake); out.flush()
            sleep(3)
            setTtl(fd, 3)
            out.write(data, 0, splitPos); out.flush()
            sleep(3)
            setTtl(fd, 64)
            out.write(data, splitPos, data.size - splitPos); out.flush()
        } finally {
            try { fd.detachFd() } catch (_: Throwable) {}
        }
    }

    /**
     * Минимальный валидный TLS 1.2 Client Hello с заданным SNI.
     * DPI парсит его, видит SNI=ya.ru, помечает conntrack-запись.
     * Структура:
     *   Record Header (5b)
     *   Handshake Header (4b)
     *   ClientHello: version(2) + random(32) + sid(1+0) + cipherSuites(2+N)
     *              + compression(1+1) + extensions(2+...)
     *   Extensions: SNI + supported_versions + signature_algorithms
     */
    private fun buildFakeClientHello(sni: String): ByteArray {
        val sniBytes = sni.toByteArray(Charsets.US_ASCII)

        // SNI extension body
        // server_name list: list_length(2) + name_type(1) + name_length(2) + name
        val sniExt = ByteArrayOutputStream()
        val sniListBody = ByteArrayOutputStream()
        sniListBody.write(0x00)  // host_name type
        sniListBody.write((sniBytes.size shr 8) and 0xFF)
        sniListBody.write(sniBytes.size and 0xFF)
        sniListBody.write(sniBytes)
        val sniListBytes = sniListBody.toByteArray()
        sniExt.write(0x00); sniExt.write(0x00)  // ext type 0x0000 = SNI
        sniExt.write((sniListBytes.size + 2) shr 8); sniExt.write((sniListBytes.size + 2) and 0xFF)
        sniExt.write((sniListBytes.size shr 8) and 0xFF); sniExt.write(sniListBytes.size and 0xFF)
        sniExt.write(sniListBytes)

        // supported_versions: TLS 1.2 only
        val supVer = byteArrayOf(0x00, 0x2B, 0x00, 0x03, 0x02, 0x03, 0x03)
        // signature_algorithms: rsa_pkcs1_sha256
        val sigAlg = byteArrayOf(0x00, 0x0D, 0x00, 0x04, 0x00, 0x02, 0x04, 0x01)

        val extensionsBody = ByteArrayOutputStream()
        extensionsBody.write(sniExt.toByteArray())
        extensionsBody.write(supVer)
        extensionsBody.write(sigAlg)
        val extBytes = extensionsBody.toByteArray()

        // Client Hello body
        val chBody = ByteArrayOutputStream()
        chBody.write(0x03); chBody.write(0x03)  // legacy_version TLS 1.2
        val random = ByteArray(32)
        Random.Default.nextBytes(random)
        chBody.write(random)
        chBody.write(0x00)  // session_id empty
        // cipher_suites: TLS_RSA_WITH_AES_128_GCM_SHA256
        chBody.write(0x00); chBody.write(0x02)
        chBody.write(0x00); chBody.write(0x9C)
        chBody.write(0x01); chBody.write(0x00)  // compression null
        chBody.write((extBytes.size shr 8) and 0xFF); chBody.write(extBytes.size and 0xFF)
        chBody.write(extBytes)
        val chBytes = chBody.toByteArray()

        // Handshake header: type(1) + length(3)
        val hs = ByteArrayOutputStream()
        hs.write(0x01)  // client_hello
        hs.write(0x00)
        hs.write((chBytes.size shr 8) and 0xFF); hs.write(chBytes.size and 0xFF)
        hs.write(chBytes)
        val hsBytes = hs.toByteArray()

        // Record layer: type(1) + version(2) + length(2) + content
        val rec = ByteArrayOutputStream()
        rec.write(0x16)  // handshake
        rec.write(0x03); rec.write(0x01)  // TLS 1.0 в record layer
        rec.write((hsBytes.size shr 8) and 0xFF); rec.write(hsBytes.size and 0xFF)
        rec.write(hsBytes)
        return rec.toByteArray()
    }

    // ─── helpers ──────────────────────────────────────────────
    @Volatile private var ttlSupported: Boolean? = null

    private fun setTtl(pfd: ParcelFileDescriptor, ttl: Int): Boolean {
        return try {
            Os.setsockoptInt(pfd.fileDescriptor, OsConstants.IPPROTO_IP, OsConstants.IP_TTL, ttl)
            if (ttlSupported != true) {
                ttlSupported = true
                Log.i(TAG, "setsockopt IP_TTL=$ttl OK — TTL стратегии работают")
            }
            true
        } catch (t: Throwable) {
            if (ttlSupported != false) {
                ttlSupported = false
                Log.e(TAG, "setsockopt IP_TTL=$ttl FAILED: ${t.message} — TTL стратегии БЕСПОЛЕЗНЫ")
            }
            false
        }
    }

    private fun sleep(ms: Int) {
        try { Thread.sleep(ms.toLong()) } catch (_: InterruptedException) {}
    }

    private fun looksLikeTlsClientHello(d: ByteArray): Boolean {
        if (d.size < 43) return false
        if (d[0] != 0x16.toByte()) return false
        if (d[1] != 0x03.toByte()) return false
        if (d[5] != 0x01.toByte()) return false
        return true
    }

    internal fun findSniRange(data: ByteArray): Pair<Int, Int>? {
        try {
            var p = 9
            if (p + 2 > data.size) return null
            p += 2; p += 32
            if (p + 1 > data.size) return null
            val sidLen = data[p].toInt() and 0xFF
            p += 1 + sidLen
            if (p + 2 > data.size) return null
            val csLen = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
            p += 2 + csLen
            if (p + 1 > data.size) return null
            val cmLen = data[p].toInt() and 0xFF
            p += 1 + cmLen
            if (p + 2 > data.size) return null
            val extLen = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
            p += 2
            val extEnd = p + extLen
            if (extEnd > data.size) return null
            while (p + 4 <= extEnd) {
                val extType = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
                val extLength = ((data[p + 2].toInt() and 0xFF) shl 8) or (data[p + 3].toInt() and 0xFF)
                p += 4
                if (p + extLength > extEnd) return null
                if (extType == 0x0000) {
                    if (extLength < 5) return null
                    val q = p + 5
                    val hostLen = ((data[p + 3].toInt() and 0xFF) shl 8) or (data[p + 4].toInt() and 0xFF)
                    if (q + hostLen > p + extLength) return null
                    return Pair(q, q + hostLen)
                }
                p += extLength
            }
            return null
        } catch (_: Throwable) { return null }
    }

    /** Извлекает SNI из Client Hello как строку. Возвращает null если не TLS. */
    fun extractSni(data: ByteArray): String? {
        val range = findSniRange(data) ?: return null
        if (range.first < 0 || range.second > data.size || range.first >= range.second) return null
        return try { String(data, range.first, range.second - range.first, Charsets.US_ASCII) }
        catch (_: Throwable) { null }
    }
}
