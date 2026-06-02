package com.amsales.vpn.tgproxy

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor

/**
 * FakeTLS обёртка для MTProto — портировано с tg_ws_proxy/proxy/fake_tls.py
 *
 * Telegram при подключении через ee-секрет шлёт TLS Client Hello, в котором
 * client_random содержит HMAC-SHA256(secret, all-zero-client-hello)[:28].
 * Мы это проверяем и отвечаем своим Server Hello + ChangeCipherSpec + ApplicationData.
 * Дальше всё MTProto обёрнуто в TLS-ApplicationData records (тип 0x17).
 */
object FakeTls {
    const val TLS_RECORD_HANDSHAKE: Byte = 0x16
    const val TLS_RECORD_CCS: Byte = 0x14
    const val TLS_RECORD_APPDATA: Byte = 0x17

    private const val CLIENT_RANDOM_OFFSET = 11
    private const val CLIENT_RANDOM_LEN = 32
    private const val SESSION_ID_OFFSET = 44
    private const val SESSION_ID_LEN = 32
    private const val TIMESTAMP_TOLERANCE = 120
    private const val TLS_APPDATA_MAX = 16384

    private val CCS_FRAME = byteArrayOf(0x14, 0x03, 0x03, 0x00, 0x01, 0x01)

    private val SERVER_HELLO_TEMPLATE: ByteArray = run {
        val out = mutableListOf<Byte>()
        out.addAll(byteArrayOf(0x16, 0x03, 0x03, 0x00, 0x7A).toList())          // record hdr
        out.addAll(byteArrayOf(0x02, 0x00, 0x00, 0x76).toList())                 // hs hdr
        out.addAll(byteArrayOf(0x03, 0x03).toList())                              // version 1.2
        repeat(32) { out.add(0) }                                                 // server_random
        out.add(0x20.toByte())                                                    // session_id len
        repeat(32) { out.add(0) }                                                 // session_id
        out.addAll(byteArrayOf(0x13, 0x01, 0x00).toList())                        // cipher
        out.addAll(byteArrayOf(0x00, 0x2E).toList())                              // ext len
        out.addAll(byteArrayOf(0x00, 0x33, 0x00, 0x24, 0x00, 0x1D, 0x00, 0x20).toList())
        repeat(32) { out.add(0) }                                                 // pubkey
        out.addAll(byteArrayOf(0x00, 0x2B, 0x00, 0x02, 0x03, 0x04).toList())      // supported_versions
        out.toByteArray()
    }

    private const val SH_RANDOM_OFF = 11
    private const val SH_SESSID_OFF = 44
    private const val SH_PUBKEY_OFF = 89

    private val rnd = SecureRandom()

    data class ClientHelloOk(
        val clientRandom: ByteArray,
        val sessionId: ByteArray,
        val timestamp: Int,
    )

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun verifyClientHello(data: ByteArray, secret: ByteArray): ClientHelloOk? {
        if (data.size < 43) return null
        if (data[0] != TLS_RECORD_HANDSHAKE) return null
        if (data[5] != 0x01.toByte()) return null

        val clientRandom = data.copyOfRange(CLIENT_RANDOM_OFFSET,
            CLIENT_RANDOM_OFFSET + CLIENT_RANDOM_LEN)

        val zeroed = data.copyOf()
        for (i in 0 until CLIENT_RANDOM_LEN) {
            zeroed[CLIENT_RANDOM_OFFSET + i] = 0
        }
        val expected = hmacSha256(secret, zeroed)

        // constant-time compare первые 28 байт
        var ok = 0
        for (i in 0 until 28) ok = ok or (expected[i].toInt() xor clientRandom[i].toInt())
        if (ok != 0) return null

        // последние 4 байта — XOR-зашифрованный timestamp
        val tsXor = ByteArray(4)
        for (i in 0 until 4) {
            tsXor[i] = (clientRandom[28 + i] xor expected[28 + i])
        }
        val timestamp = ((tsXor[0].toInt() and 0xFF)) or
            ((tsXor[1].toInt() and 0xFF) shl 8) or
            ((tsXor[2].toInt() and 0xFF) shl 16) or
            ((tsXor[3].toInt() and 0xFF) shl 24)

        val now = (System.currentTimeMillis() / 1000).toInt()
        if (kotlin.math.abs(now - timestamp) > TIMESTAMP_TOLERANCE) return null

        val sessionId = if (data.size >= SESSION_ID_OFFSET + SESSION_ID_LEN
                            && data[43] == 0x20.toByte()) {
            data.copyOfRange(SESSION_ID_OFFSET, SESSION_ID_OFFSET + SESSION_ID_LEN)
        } else ByteArray(SESSION_ID_LEN)

        return ClientHelloOk(clientRandom, sessionId, timestamp)
    }

    fun buildServerHello(secret: ByteArray, clientRandom: ByteArray, sessionId: ByteArray): ByteArray {
        val sh = SERVER_HELLO_TEMPLATE.copyOf()
        System.arraycopy(sessionId, 0, sh, SH_SESSID_OFF, 32)
        val pubkey = ByteArray(32).also { rnd.nextBytes(it) }
        System.arraycopy(pubkey, 0, sh, SH_PUBKEY_OFF, 32)

        val encSize = 1900 + rnd.nextInt(201)
        val encrypted = ByteArray(encSize).also { rnd.nextBytes(it) }
        val appRecord = ByteArray(5 + encSize).apply {
            this[0] = 0x17
            this[1] = 0x03
            this[2] = 0x03
            this[3] = ((encSize ushr 8) and 0xFF).toByte()
            this[4] = (encSize and 0xFF).toByte()
            System.arraycopy(encrypted, 0, this, 5, encSize)
        }

        val response = ByteArray(sh.size + CCS_FRAME.size + appRecord.size)
        System.arraycopy(sh, 0, response, 0, sh.size)
        System.arraycopy(CCS_FRAME, 0, response, sh.size, CCS_FRAME.size)
        System.arraycopy(appRecord, 0, response, sh.size + CCS_FRAME.size, appRecord.size)

        val hmacInput = ByteArray(clientRandom.size + response.size)
        System.arraycopy(clientRandom, 0, hmacInput, 0, clientRandom.size)
        System.arraycopy(response, 0, hmacInput, clientRandom.size, response.size)
        val serverRandom = hmacSha256(secret, hmacInput)

        System.arraycopy(serverRandom, 0, response, SH_RANDOM_OFF, 32)
        return response
    }

    /** Обернуть data в один или несколько TLS-ApplicationData record-ов. */
    fun wrapTlsRecord(data: ByteArray): ByteArray {
        if (data.size <= TLS_APPDATA_MAX) {
            val out = ByteArray(5 + data.size)
            out[0] = 0x17
            out[1] = 0x03
            out[2] = 0x03
            out[3] = ((data.size ushr 8) and 0xFF).toByte()
            out[4] = (data.size and 0xFF).toByte()
            System.arraycopy(data, 0, out, 5, data.size)
            return out
        }
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        var total = 0
        while (offset < data.size) {
            val len = minOf(TLS_APPDATA_MAX, data.size - offset)
            val chunk = ByteArray(5 + len)
            chunk[0] = 0x17; chunk[1] = 0x03; chunk[2] = 0x03
            chunk[3] = ((len ushr 8) and 0xFF).toByte()
            chunk[4] = (len and 0xFF).toByte()
            System.arraycopy(data, offset, chunk, 5, len)
            chunks.add(chunk)
            offset += len
            total += chunk.size
        }
        val out = ByteArray(total)
        var pos = 0
        for (c in chunks) { System.arraycopy(c, 0, out, pos, c.size); pos += c.size }
        return out
    }
}

/**
 * Поток-обёртка над сокетом клиента: снаружи MTProto-байты,
 * внутри они оборачиваются в TLS Application Data records.
 */
class FakeTlsStream(
    private val input: InputStream,
    private val output: OutputStream,
) {
    private val readBuf = java.io.ByteArrayOutputStream()

    /** Прочитать ровно n байт из TLS-обёрнутого потока (распаковка). */
    fun readExactly(n: Int): ByteArray {
        while (readBuf.size() < n) {
            val payload = readTlsPayload() ?: throw java.io.EOFException("FakeTLS short read")
            readBuf.write(payload)
        }
        val arr = readBuf.toByteArray()
        val out = arr.copyOfRange(0, n)
        readBuf.reset()
        if (arr.size > n) readBuf.write(arr, n, arr.size - n)
        return out
    }

    fun read(maxLen: Int): ByteArray {
        if (readBuf.size() > 0) {
            val arr = readBuf.toByteArray()
            readBuf.reset()
            return if (arr.size <= maxLen) arr else {
                readBuf.write(arr, maxLen, arr.size - maxLen)
                arr.copyOfRange(0, maxLen)
            }
        }
        val payload = readTlsPayload() ?: return ByteArray(0)
        if (payload.size <= maxLen) return payload
        readBuf.write(payload, maxLen, payload.size - maxLen)
        return payload.copyOfRange(0, maxLen)
    }

    private fun readTlsPayload(): ByteArray? {
        while (true) {
            val hdr = readN(input, 5) ?: return null
            val rtype = hdr[0]
            val recLen = ((hdr[3].toInt() and 0xFF) shl 8) or (hdr[4].toInt() and 0xFF)
            if (rtype == FakeTls.TLS_RECORD_CCS) {
                if (recLen > 0) readN(input, recLen) ?: return null
                continue
            }
            if (rtype != FakeTls.TLS_RECORD_APPDATA) return null
            return readN(input, recLen)
        }
    }

    fun write(data: ByteArray) {
        output.write(FakeTls.wrapTlsRecord(data))
    }

    fun flush() = output.flush()

    fun close() {
        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}
    }

    companion object {
        fun readN(input: InputStream, n: Int): ByteArray? {
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = input.read(buf, read, n - read)
                if (r < 0) return null
                read += r
            }
            return buf
        }
    }
}
