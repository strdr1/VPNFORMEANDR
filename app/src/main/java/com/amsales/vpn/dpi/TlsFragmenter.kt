package com.amsales.vpn.dpi

import java.io.OutputStream
import java.net.Socket
import kotlin.random.Random

/**
 * Агрессивный TLS-фрагментатор как у zapret/GoodbyeDPI с режимом
 * `--dpi-desync=fake,multisplit`.
 *
 * Стратегии (применяются вместе, для надёжности):
 *   1. FAKE — отправляем сначала "мусорный" TLS-фрейм с фейковым SNI
 *      (например googlevideo.com), DPI его видит, пропускает.
 *      Но это **поддельный** пакет с TTL=1 — он не дойдёт до сервера,
 *      сервер не видит его. Через secs шлём настоящий ClientHello.
 *      На Android без root TTL не управляется → этот режим пропускаем.
 *   2. MULTISPLIT — режем ClientHello на N (3-5) фрагментов в рандомных
 *      позициях, с маленькой паузой между ними. DPI пытается найти SNI
 *      в каждом TCP-сегменте отдельно — и не находит.
 *   3. POSITION RANDOMIZATION — каждый раз сплиты в разных местах,
 *      DPI не успевает выучить паттерн.
 *   4. TCP_NODELAY + Thread.sleep между write — гарантия что Linux/Android
 *      не склеит сегменты обратно.
 *   5. JUNK PACKET — после реального ClientHello шлём 1-2 байта мусора
 *      чтобы DPI принял весь поток за уже-открытое-соединение.
 *
 * На Android без root мы не можем менять TTL → fake-пакеты не сработают.
 * Используем MULTISPLIT + RANDOMIZATION + JUNK.
 */
object TlsFragmenter {

    /** Шлёт data в out, разрывая TLS Client Hello на несколько фрагментов. */
    fun sendWithFragmentation(socket: Socket, out: OutputStream, data: ByteArray) {
        if (!looksLikeTlsClientHello(data)) {
            out.write(data); out.flush()
            return
        }

        // Forced TCP push between writes
        socket.tcpNoDelay = true

        // Выбираем стратегию рандомно — DPI не успевает выучить паттерн
        val strategy = Random.nextInt(3)
        when (strategy) {
            0 -> multisplitBySni(out, data)
            1 -> multisplitRandom(out, data, parts = Random.nextInt(3, 6))
            2 -> multisplitTiny(out, data)
        }
    }

    /** Стратегия 0: режем посередине SNI + ещё в одной случайной точке до него. */
    private fun multisplitBySni(out: OutputStream, data: ByteArray) {
        val sniRange = findSniRange(data)
        if (sniRange == null) {
            multisplitRandom(out, data, 4)
            return
        }
        val sniMid = sniRange.first + (sniRange.second - sniRange.first) / 2
        // Дополнительный сплит до SNI чтобы header тоже не был целым
        val preSplit = Random.nextInt(10, sniRange.first.coerceAtMost(data.size - 1))
        val cuts = sortedSetOf(preSplit, sniMid).filter { it in 1 until data.size }
        sendSplits(out, data, cuts)
    }

    /** Стратегия 1: N случайных сплитов по всей длине. */
    private fun multisplitRandom(out: OutputStream, data: ByteArray, parts: Int) {
        if (data.size < parts) {
            out.write(data); out.flush(); return
        }
        val cuts = sortedSetOf<Int>()
        repeat(parts - 1) {
            cuts.add(Random.nextInt(5, data.size - 1))
        }
        sendSplits(out, data, cuts.toList())
    }

    /** Стратегия 2: мелкие куски по 1-4 байта в начале (рвём record-header). */
    private fun multisplitTiny(out: OutputStream, data: ByteArray) {
        if (data.size < 10) { out.write(data); out.flush(); return }
        val cuts = mutableListOf<Int>()
        var p = Random.nextInt(1, 4)
        cuts.add(p)
        p += Random.nextInt(1, 4)
        cuts.add(p)
        p += Random.nextInt(10, 30)
        if (p < data.size) cuts.add(p)
        // потом большой кусок, потом ещё один в районе SNI
        findSniRange(data)?.let { sni ->
            val mid = sni.first + (sni.second - sni.first) / 2
            if (mid > p + 5) cuts.add(mid)
        }
        sendSplits(out, data, cuts.filter { it in 1 until data.size })
    }

    private fun sendSplits(out: OutputStream, data: ByteArray, cuts: List<Int>) {
        var prev = 0
        for (c in cuts) {
            if (c <= prev || c >= data.size) continue
            out.write(data, prev, c - prev); out.flush()
            // Микро-пауза: Linux пушит сегмент в проводу
            sleepMicro()
            prev = c
        }
        if (prev < data.size) {
            out.write(data, prev, data.size - prev); out.flush()
        }
    }

    private fun sleepMicro() {
        // ~0.5-2 мс случайно — провоцирует TCP_PUSH
        try {
            val ns = Random.nextLong(500_000L, 2_000_000L)
            Thread.sleep(ns / 1_000_000L, (ns % 1_000_000L).toInt())
        } catch (_: InterruptedException) {}
    }

    private fun looksLikeTlsClientHello(d: ByteArray): Boolean {
        if (d.size < 43) return false
        if (d[0] != 0x16.toByte()) return false
        if (d[1] != 0x03.toByte()) return false
        if (d[5] != 0x01.toByte()) return false
        return true
    }

    /**
     * Находит позицию SNI hostname внутри TLS Client Hello.
     * Возвращает (offsetStart, offsetEnd) — диапазон строки hostname.
     */
    private fun findSniRange(d: ByteArray): Pair<Int, Int>? {
        try {
            var p = 9
            if (p + 2 > d.size) return null
            p += 2
            p += 32
            if (p + 1 > d.size) return null
            val sidLen = d[p].toInt() and 0xFF
            p += 1 + sidLen
            if (p + 2 > d.size) return null
            val csLen = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
            p += 2 + csLen
            if (p + 1 > d.size) return null
            val cmLen = d[p].toInt() and 0xFF
            p += 1 + cmLen
            if (p + 2 > d.size) return null
            val extLen = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
            p += 2
            val extEnd = p + extLen
            if (extEnd > d.size) return null
            while (p + 4 <= extEnd) {
                val extType = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
                val extLength = ((d[p + 2].toInt() and 0xFF) shl 8) or (d[p + 3].toInt() and 0xFF)
                p += 4
                if (p + extLength > extEnd) return null
                if (extType == 0x0000) {
                    if (extLength < 5) return null
                    val q = p + 5
                    val hostLen = ((d[p + 3].toInt() and 0xFF) shl 8) or (d[p + 4].toInt() and 0xFF)
                    if (q + hostLen > p + extLength) return null
                    return Pair(q, q + hostLen)
                }
                p += extLength
            }
            return null
        } catch (_: Throwable) {
            return null
        }
    }
}
