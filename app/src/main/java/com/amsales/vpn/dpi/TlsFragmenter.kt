package com.amsales.vpn.dpi

import java.io.OutputStream
import java.net.Socket
import kotlin.random.Random

/**
 * TLS-фрагментация для обхода DPI на Android (без root).
 *
 * Комбинируем 2 техники которые работают без CAP_NET_RAW:
 *
 * 1) TLS RECORD REFRAMING (главное):
 *    Берём один большой TLS Handshake record (0x16) и разбиваем его на
 *    2-3 валидных TLS record. Каждый record — короче 100 байт, со своим
 *    5-байт header (тип=0x16, version=0x0303, length).
 *
 *    Сервер собирает Handshake message правильно (это легально по RFC 5246
 *    §6.2.1 "Records may not span key changes" — внутри одного handshake
 *    fragmentation разрешена).
 *
 *    DPI парсит КАЖДЫЙ TLS record по отдельности и ищет SNI extension в
 *    нём. Если record короче чем оффсет SNI — DPI не находит SNI.
 *
 * 2) TCP SEGMENT SPLIT (дополнительно):
 *    Между TLS record делаем write+flush+sleep — гарантия что Linux
 *    отправит каждый TLS record отдельным TCP-сегментом.
 *
 * Это эквивалент `--dpi-desync=multisplit --dpi-desync-split-pos=1`
 * из zapret/winws (без `fake`, `seqovl`, `fooling=ts` — они требуют
 * raw socket).
 */
object TlsFragmenter {

    fun sendWithFragmentation(socket: Socket, out: OutputStream, data: ByteArray) {
        if (!looksLikeTlsClientHello(data)) {
            out.write(data); out.flush()
            return
        }
        socket.tcpNoDelay = true

        // 5-байт TLS record header
        val recVer1 = data[1]   // обычно 0x03
        val recVer2 = data[2]   // обычно 0x01 или 0x03
        val payload = data.copyOfRange(5, data.size)

        // Делим payload на 3 части. Cuts в payload-координатах.
        // Первая часть — очень маленькая (1-5 байт) чтобы рвать header
        // самого Handshake-сообщения. DPI не увидит длину Handshake.
        val sniRange = findSniRangeInPayload(payload)
        val cuts = mutableListOf<Int>()

        cuts.add(Random.nextInt(1, 6))   // первый кусочек 1-5 байт

        if (sniRange != null) {
            val (sStart, sEnd) = sniRange
            cuts.add(sStart)                                  // до hostname
            cuts.add(sStart + (sEnd - sStart) / 2)            // середина hostname
        } else {
            // fallback — режем где-то в первой трети, потом в середине
            cuts.add(payload.size / 4)
            cuts.add(payload.size / 2)
        }

        // Сортируем + убираем дубли/невалидные
        val sortedCuts = cuts.toSortedSet().filter { it in 1 until payload.size }

        // Шлём по TLS-record'у на каждый фрагмент
        var prev = 0
        for (cut in sortedCuts) {
            if (cut <= prev) continue
            sendTlsRecord(out, recVer1, recVer2, payload, prev, cut - prev)
            sleepMs(Random.nextInt(5, 12))
            prev = cut
        }
        if (prev < payload.size) {
            sendTlsRecord(out, recVer1, recVer2, payload, prev, payload.size - prev)
        }
    }

    /** Отправляет один TLS Handshake record (тип 0x16) с заданным payload. */
    private fun sendTlsRecord(
        out: OutputStream, v1: Byte, v2: Byte,
        src: ByteArray, off: Int, len: Int,
    ) {
        val rec = ByteArray(5 + len)
        rec[0] = 0x16  // Handshake
        rec[1] = v1
        rec[2] = v2
        rec[3] = ((len ushr 8) and 0xFF).toByte()
        rec[4] = (len and 0xFF).toByte()
        System.arraycopy(src, off, rec, 5, len)
        out.write(rec); out.flush()
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

    /**
     * Ищет позицию hostname внутри payload TLS Client Hello (БЕЗ 5-байт
     * record-header). Координаты в payload, не в полном TLS record.
     */
    private fun findSniRangeInPayload(payload: ByteArray): Pair<Int, Int>? {
        try {
            // payload[0]=handshake_type(1)=01, payload[1..3]=length
            var p = 4
            if (p + 2 > payload.size) return null
            p += 2   // client_version
            p += 32  // random
            if (p + 1 > payload.size) return null
            val sidLen = payload[p].toInt() and 0xFF
            p += 1 + sidLen
            if (p + 2 > payload.size) return null
            val csLen = ((payload[p].toInt() and 0xFF) shl 8) or (payload[p + 1].toInt() and 0xFF)
            p += 2 + csLen
            if (p + 1 > payload.size) return null
            val cmLen = payload[p].toInt() and 0xFF
            p += 1 + cmLen
            if (p + 2 > payload.size) return null
            val extLen = ((payload[p].toInt() and 0xFF) shl 8) or (payload[p + 1].toInt() and 0xFF)
            p += 2
            val extEnd = p + extLen
            if (extEnd > payload.size) return null
            while (p + 4 <= extEnd) {
                val extType = ((payload[p].toInt() and 0xFF) shl 8) or (payload[p + 1].toInt() and 0xFF)
                val extLength = ((payload[p + 2].toInt() and 0xFF) shl 8) or (payload[p + 3].toInt() and 0xFF)
                p += 4
                if (p + extLength > extEnd) return null
                if (extType == 0x0000) {
                    if (extLength < 5) return null
                    val q = p + 5
                    val hostLen = ((payload[p + 3].toInt() and 0xFF) shl 8) or (payload[p + 4].toInt() and 0xFF)
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
