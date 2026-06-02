package com.amsales.vpn.tgproxy

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Минимальный «сырой» WebSocket-клиент поверх TLS — для коннекта к
 * Telegram-DC или Cloudflare-воркеру.
 *
 * Особенности:
 *   - Самоподписанные/невалидные TLS-сертификаты принимаются (как в Python).
 *   - WS-фреймы строятся вручную (binary, masked).
 *   - Server hostname в TLS handshake может отличаться от connect-IP — SNI.
 */
class WsHandshakeError(
    val statusCode: Int,
    val statusLine: String,
    val location: String? = null,
) : IOException("HTTP $statusCode: $statusLine") {
    val isRedirect: Boolean get() = statusCode in setOf(301, 302, 303, 307, 308)
}

class RawWebSocket private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
) {
    @Volatile private var closed = false
    private val rnd = SecureRandom()

    companion object {
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA

        private val INSECURE_SSL: SSLSocketFactory by lazy {
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
                override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
            }
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(tm), SecureRandom())
            }.socketFactory
        }

        /**
         * @param targetHost Куда коннектиться (IP или домен).
         * @param sniDomain  SNI для TLS handshake.
         * @param path       HTTP path для WS handshake (e.g. "/apiws").
         */
        fun connect(
            targetHost: String,
            sniDomain: String,
            path: String = "/apiws",
            timeoutMs: Int = 10000,
        ): RawWebSocket {
            val plain = Socket()
            plain.connect(InetSocketAddress(targetHost, 443), timeoutMs)
            plain.tcpNoDelay = true
            plain.soTimeout = timeoutMs

            val ssl = INSECURE_SSL.createSocket(plain, sniDomain, 443, true) as SSLSocket
            ssl.enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
            ssl.startHandshake()

            val input = ssl.inputStream
            val output = ssl.outputStream

            val wsKey = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
            val req = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(sniDomain).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("Sec-WebSocket-Protocol: binary\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)
            output.write(req)
            output.flush()

            val lines = readHttpResponse(input)
            if (lines.isEmpty()) {
                ssl.close(); throw WsHandshakeError(0, "empty response")
            }
            val firstLine = lines[0]
            val parts = firstLine.split(" ", limit = 3)
            val status = parts.getOrNull(1)?.toIntOrNull() ?: 0
            if (status == 101) {
                ssl.soTimeout = 0
                return RawWebSocket(ssl, input, output)
            }
            val headers = mutableMapOf<String, String>()
            for (l in lines.drop(1)) {
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }
            ssl.close()
            throw WsHandshakeError(status, firstLine, headers["location"])
        }

        private fun readHttpResponse(input: InputStream): List<String> {
            val lines = mutableListOf<String>()
            val sb = StringBuilder()
            var prev = -1
            while (true) {
                val b = input.read()
                if (b < 0) break
                if (b == '\n'.code) {
                    val line = sb.toString().trimEnd('\r')
                    if (line.isEmpty()) break
                    lines.add(line)
                    sb.clear()
                } else {
                    sb.append(b.toChar())
                }
                prev = b
            }
            return lines
        }
    }

    fun send(data: ByteArray) {
        if (closed) throw IOException("WebSocket closed")
        val frame = buildFrame(OP_BINARY, data, mask = true)
        synchronized(output) {
            output.write(frame); output.flush()
        }
    }

    fun sendBatch(parts: List<ByteArray>) {
        if (closed) throw IOException("WebSocket closed")
        synchronized(output) {
            for (p in parts) output.write(buildFrame(OP_BINARY, p, mask = true))
            output.flush()
        }
    }

    /** @return null если соединение закрылось. */
    fun recv(): ByteArray? {
        while (!closed) {
            val (opcode, payload) = readFrame() ?: return null
            when (opcode) {
                OP_CLOSE -> {
                    closed = true
                    try {
                        synchronized(output) {
                            output.write(buildFrame(OP_CLOSE,
                                if (payload.size >= 2) payload.copyOfRange(0, 2) else ByteArray(0),
                                mask = true))
                            output.flush()
                        }
                    } catch (_: Exception) {}
                    return null
                }
                OP_PING -> {
                    try {
                        synchronized(output) {
                            output.write(buildFrame(OP_PONG, payload, mask = true))
                            output.flush()
                        }
                    } catch (_: Exception) {}
                }
                OP_PONG -> { /* skip */ }
                0x1, 0x2 -> return payload
                else -> { /* skip */ }
            }
        }
        return null
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            synchronized(output) {
                output.write(buildFrame(OP_CLOSE, ByteArray(0), mask = true))
                output.flush()
            }
        } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
    }

    fun isClosed() = closed

    private fun buildFrame(opcode: Int, data: ByteArray, mask: Boolean): ByteArray {
        val len = data.size
        val fb = (0x80 or opcode).toByte()

        if (!mask) {
            return when {
                len < 126 -> byteArrayOf(fb, len.toByte()) + data
                len < 65536 -> byteArrayOf(fb, 126.toByte(),
                    ((len ushr 8) and 0xFF).toByte(), (len and 0xFF).toByte()) + data
                else -> {
                    val hdr = ByteArray(10)
                    hdr[0] = fb; hdr[1] = 127.toByte()
                    for (i in 0 until 8) hdr[2 + i] = ((len ushr ((7 - i) * 8)) and 0xFF).toByte()
                    hdr + data
                }
            }
        }

        val mk = ByteArray(4).also { rnd.nextBytes(it) }
        val masked = ByteArray(len)
        for (i in 0 until len) masked[i] = (data[i].toInt() xor mk[i and 3].toInt()).toByte()

        return when {
            len < 126 -> byteArrayOf(fb, (0x80 or len).toByte()) + mk + masked
            len < 65536 -> byteArrayOf(fb, (0x80 or 126).toByte(),
                ((len ushr 8) and 0xFF).toByte(), (len and 0xFF).toByte()) + mk + masked
            else -> {
                val hdr = ByteArray(10)
                hdr[0] = fb; hdr[1] = (0x80 or 127).toByte()
                for (i in 0 until 8) hdr[2 + i] = ((len ushr ((7 - i) * 8)) and 0xFF).toByte()
                hdr + mk + masked
            }
        }
    }

    private fun readFrame(): Pair<Int, ByteArray>? {
        val hdr = FakeTlsStream.readN(input, 2) ?: return null
        val opcode = hdr[0].toInt() and 0x0F
        var len = hdr[1].toInt() and 0x7F
        val masked = (hdr[1].toInt() and 0x80) != 0

        if (len == 126) {
            val ext = FakeTlsStream.readN(input, 2) ?: return null
            len = ((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)
        } else if (len == 127) {
            val ext = FakeTlsStream.readN(input, 8) ?: return null
            var v = 0L
            for (b in ext) v = (v shl 8) or (b.toLong() and 0xFF)
            len = v.toInt()
        }

        val mk = if (masked) FakeTlsStream.readN(input, 4) else null
        val payload = if (len == 0) ByteArray(0) else FakeTlsStream.readN(input, len) ?: return null
        if (mk != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor mk[i and 3].toInt()).toByte()
        }
        return Pair(opcode, payload)
    }
}
