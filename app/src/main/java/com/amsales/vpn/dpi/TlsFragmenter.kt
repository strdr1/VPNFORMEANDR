package com.amsales.vpn.dpi

import java.io.OutputStream
import java.net.Socket
import kotlin.random.Random

/**
 * TLS-фрагментация на TCP-уровне для обхода DPI (без root).
 *
 * Подход (по образцу byedpi --split=N + рабочих 2026 zapret-пресетов):
 *  1. Резать ClientHello на МЕЛКИЕ куски (особенно первые 30-100 байт)
 *     именно на TCP-уровне через write+flush+sleep.
 *  2. Точки разрезов:
 *     a) В первых 10 байт (рвём record header — provider не парсит)
 *     b) Перед SNI (host_pos)
 *     c) В середине SNI hostname
 *     d) После SNI
 *  3. Между write() — sleep 5-15мс. Это **гарантирует** что Linux
 *     TCP layer отправит каждый кусок отдельным сегментом (иначе
 *     при tcpNoDelay=false он может собрать всё в один сегмент).
 *  4. tcpNoDelay = true для немедленного отправки.
 *
 * Никаких манипуляций с TLS record-type или TLS-level фрагментацией —
 * это рискует сломать handshake. Только разделение на TCP-уровне.
 */
object TlsFragmenter {

    fun sendWithFragmentation(socket: Socket, out: OutputStream, data: ByteArray) {
        if (!looksLikeTlsClientHello(data)) {
            out.write(data); out.flush()
            return
        }
        socket.tcpNoDelay = true

        // Находим SNI чтобы рвать прицельно
        val sni = findSniRange(data)
        val cuts = sortedSetOf<Int>()

        if (sni != null) {
            // Прицельные разрезы вокруг SNI — самый эффективный
            val (sStart, sEnd) = sni
            // Точка перед SNI (где провайдер ожидает увидеть hostname)
            cuts.add(sStart)
            // Точка в МИДДЛЕ SNI (рвём название домена)
            cuts.add(sStart + (sEnd - sStart) / 2)
            // Точка сразу после SNI
            if (sEnd < data.size) cuts.add(sEnd)
            // Ранний split — рвём TLS header
            cuts.add(Random.nextInt(3, 8))
        } else {
            // Fallback — много случайных мелких кусков в начале
            cuts.add(Random.nextInt(1, 5))
            cuts.add(Random.nextInt(5, 15))
            cuts.add(Random.nextInt(20, 50))
            cuts.add(Random.nextInt(50, 100).coerceAtMost(data.size - 1))
        }

        // Дополнительно — обязательно рвём record header
        cuts.add(1)
        cuts.add(3)

        // Отправляем по кускам с задержкой
        var prev = 0
        for (c in cuts.filter { it in 1 until data.size }) {
            if (c <= prev) continue
            out.write(data, prev, c - prev); out.flush()
            // Sleep 5-15мс гарантирует отдельные TCP-сегменты
            sleepMs(Random.nextInt(5, 15))
            prev = c
        }
        if (prev < data.size) {
            out.write(data, prev, data.size - prev); out.flush()
        }
    }

    private fun sleepMs(ms: Int) {
        try { Thread.sleep(ms.toLong()) } catch (_: InterruptedException) {}
    }

    private fun looksLikeTlsClientHello(d: ByteArray): Boolean {
        if (d.size < 43) return false
        if (d[0] != 0x16.toByte()) return false
        if (d[1] != 0x03.toByte()) return false
        if (d[5] != 0x01.toByte()) return false
        return true
    }

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
