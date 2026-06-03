package com.amsales.vpn.dpi

import java.io.OutputStream
import kotlin.random.Random

/**
 * Фрагментатор TLS Client Hello — режет первый исходящий пакет на 2-3
 * куска так чтобы провайдерский DPI не смог собрать SNI-расширение.
 *
 * Зачем: DPI смотрит на одну TCP-пэйлоад и ищет в нём SNI с поднадзорным
 * доменом (youtube.com и т.п.). Если SNI разорван между TCP-сегментами
 * — DPI не находит его и пропускает пакет.
 *
 * Алгоритм (как zapret):
 *  1. Опознаём что это TLS Handshake (первый байт 0x16).
 *  2. Находим позицию SNI в Client Hello.
 *  3. Режем буфер на 2 куска: до середины SNI + остаток.
 *  4. Отправляем с микро-flush между ними.
 *
 * Если буфер не похож на Client Hello — отправляем как есть.
 */
object TlsFragmenter {

    /** Шлёт data в out, при необходимости разрезая TLS Client Hello. */
    fun sendWithFragmentation(out: OutputStream, data: ByteArray) {
        if (!looksLikeTlsClientHello(data)) {
            out.write(data); out.flush()
            return
        }
        val sniRange = findSniRange(data)
        if (sniRange == null) {
            // TLS-handshake без SNI или невалидный — режем посередине
            // на всякий случай.
            val mid = data.size / 2
            out.write(data, 0, mid); out.flush()
            // Микро-пауза провоцирует разные TCP-сегменты
            try { Thread.sleep(1) } catch (_: InterruptedException) {}
            out.write(data, mid, data.size - mid); out.flush()
            return
        }
        // sniRange = [start, end) внутри data
        val splitAt = sniRange.first + (sniRange.second - sniRange.first) / 2
        out.write(data, 0, splitAt); out.flush()
        try { Thread.sleep(1) } catch (_: InterruptedException) {}
        out.write(data, splitAt, data.size - splitAt); out.flush()
    }

    private fun looksLikeTlsClientHello(d: ByteArray): Boolean {
        // 0x16 = Handshake record, версия 0x0301-0x0303, тип 0x01 (ClientHello)
        if (d.size < 43) return false
        if (d[0] != 0x16.toByte()) return false
        if (d[1] != 0x03.toByte()) return false
        // d[5] — handshake type, 1 = ClientHello
        if (d[5] != 0x01.toByte()) return false
        return true
    }

    /**
     * Находит позицию SNI hostname внутри TLS Client Hello.
     * Возвращает (offsetStart, offsetEnd) — диапазон строки hostname.
     * Возвращает null если не нашёл.
     */
    private fun findSniRange(d: ByteArray): Pair<Int, Int>? {
        try {
            // TLS record: 5 байт header. Внутри handshake.
            // Handshake header: 1 байт type + 3 байта length = 4 байта.
            // ClientHello начинается с offset 9.
            var p = 9
            if (p + 2 > d.size) return null
            // ClientVersion (2 байта)
            p += 2
            // Random (32 байта)
            p += 32
            if (p + 1 > d.size) return null
            // session_id length
            val sidLen = d[p].toInt() and 0xFF
            p += 1 + sidLen
            if (p + 2 > d.size) return null
            // cipher_suites_length (2 байта) + содержимое
            val csLen = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
            p += 2 + csLen
            if (p + 1 > d.size) return null
            // compression_methods_length (1 байт) + содержимое
            val cmLen = d[p].toInt() and 0xFF
            p += 1 + cmLen
            if (p + 2 > d.size) return null
            // extensions_length (2 байта)
            val extLen = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
            p += 2
            val extEnd = p + extLen
            if (extEnd > d.size) return null
            // Идём по extensions
            while (p + 4 <= extEnd) {
                val extType = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
                val extLength = ((d[p + 2].toInt() and 0xFF) shl 8) or (d[p + 3].toInt() and 0xFF)
                p += 4
                if (p + extLength > extEnd) return null
                if (extType == 0x0000) {
                    // server_name extension
                    // server_name_list_length (2) + name_type (1) + host_name_length (2) + host_name
                    if (extLength < 5) return null
                    val q = p + 5  // skip list_length(2) + type(1) + name_length(2)
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
