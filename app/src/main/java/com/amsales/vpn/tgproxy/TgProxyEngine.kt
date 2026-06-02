package com.amsales.vpn.tgproxy

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * MTProto WS-Bridge proxy — портировано с tg_ws_proxy.py.
 *
 * Поднимает TCP-сервер на 127.0.0.1:1443. Telegram-клиент шлёт MTProto-стрим
 * (опционально обёрнутый в Fake TLS), мы расшифровываем, перешифровываем
 * под обфускацию для Telegram-сервера и проксируем через WebSocket к
 * kwsN.web.telegram.org (или Cloudflare Worker как fallback).
 *
 * Поток:
 *   1. Клиент → 64 байта handshake (или TLS-ClientHello → server hello → 64б внутри)
 *   2. Из handshake восстанавливаем prekey+iv, ключ = SHA256(prekey+secret)
 *   3. Декриптуем 64б, читаем proto_tag+dc_id из расшифрованных байт 56..63
 *   4. Перекодируем под обфускацию TG (raw key, без secret)
 *   5. Открываем WS к kwsN.web.telegram.org, шлём наш relay_init
 *   6. Бриджим: client TCP ↔ TG WS, на лету переcrypt-ом потока
 */
class TgProxyEngine(
    private val secretHex: String,
    private val fakeTlsDomain: String = "",
    private val workerDomain: String = "",
    private val port: Int = 1443,
) {
    @Volatile var running: Boolean = false
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secret: ByteArray = hexToBytes(secretHex)

    /** Запускает прокси-сервер на 127.0.0.1:port. Идемпотентен. */
    fun start(): String {
        if (running) return generateLink()
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("127.0.0.1", port))
        serverSocket = ss
        running = true

        acceptJob = scope.launch {
            Log.i(TAG, "TG-proxy listening on 127.0.0.1:$port secret=$secretHex")
            while (isActive && running) {
                try {
                    val client = withContext(Dispatchers.IO) { ss.accept() }
                    client.tcpNoDelay = true
                    scope.launch { handleClient(client) }
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "accept failed: ${e.message}")
                    break
                }
            }
        }
        return generateLink()
    }

    fun stop() {
        if (!running) return
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        acceptJob?.cancel()
        scope.cancel()
    }

    /** Генерация tg://-ссылки для UI. */
    fun generateLink(): String {
        val server = "127.0.0.1"
        return if (fakeTlsDomain.isNotEmpty()) {
            val hex = fakeTlsDomain.toByteArray(Charsets.US_ASCII).toHex()
            "tg://proxy?server=$server&port=$port&secret=ee$secretHex$hex"
        } else {
            "tg://proxy?server=$server&port=$port&secret=dd$secretHex"
        }
    }

    private fun handleClient(client: Socket) {
        val label = "${client.inetAddress.hostAddress}:${client.port}"
        try {
            client.soTimeout = 30000
            val rawIn = client.getInputStream()
            val rawOut = client.getOutputStream()

            // 1. Прочитать handshake (опционально через FakeTLS-обёртку).
            val firstByte = ByteArray(1)
            if (rawIn.read(firstByte) != 1) return

            val triple: Triple<Any, Any, ByteArray> = if (firstByte[0] == FakeTls.TLS_RECORD_HANDSHAKE
                && fakeTlsDomain.isNotEmpty()) {
                // TLS Client Hello
                val hdrRest = FakeTlsStream.readN(rawIn, 4) ?: return
                val tlsHeader = firstByte + hdrRest
                val recLen = ((tlsHeader[3].toInt() and 0xFF) shl 8) or (tlsHeader[4].toInt() and 0xFF)
                val body = FakeTlsStream.readN(rawIn, recLen) ?: return
                val clientHello = tlsHeader + body

                val verified = FakeTls.verifyClientHello(clientHello, secret) ?: run {
                    Log.d(TAG, "[$label] Fake TLS verify failed")
                    return
                }
                val serverHello = FakeTls.buildServerHello(secret, verified.clientRandom, verified.sessionId)
                rawOut.write(serverHello); rawOut.flush()

                val tlsStream = FakeTlsStream(rawIn, rawOut)
                val hs = tlsStream.readExactly(TgProto.HANDSHAKE_LEN)
                Triple(tlsStream, tlsStream, hs)
            } else if (fakeTlsDomain.isNotEmpty()) {
                Log.d(TAG, "[$label] non-TLS byte 0x${firstByte[0].toString(16)} - redirect")
                val redirect = ("HTTP/1.1 301 Moved Permanently\r\nLocation: https://$fakeTlsDomain/\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                rawOut.write(redirect); rawOut.flush()
                return
            } else {
                val rest = FakeTlsStream.readN(rawIn, TgProto.HANDSHAKE_LEN - 1) ?: return
                val hs = firstByte + rest
                Triple(rawIn, rawOut, hs)
            }
            val clientIn = triple.first
            val clientOut = triple.second
            val handshake = triple.third

            // 2. Парсим handshake → DC, proto, ключ.
            val tryRes = tryHandshake(handshake, secret) ?: run {
                Log.w(TAG, "[$label] bad handshake (wrong secret or proto)")
                return
            }
            val (dc, isMedia, protoTag, decPrekeyIv) = tryRes
            Log.d(TAG, "[$label] handshake OK DC$dc${if (isMedia) " media" else ""}")

            // 3. Готовим relay_init для отправки в TG
            val dcIdx = if (isMedia) -dc else dc
            val relayInit = generateRelayInit(protoTag, dcIdx)

            // 4. Криптокс для re-encryption
            val ctx = buildCryptoCtx(decPrekeyIv, secret, relayInit)

            // 5. Подключаемся через WS к Telegram (или к CF Worker как fallback)
            val targetIp = TgProto.DC_REDIRECTS[dc] ?: TgProto.DC_DEFAULT_IPS[dc]
            val domains = wsDomains(dc, isMedia)
            var ws: RawWebSocket? = null

            // Сначала попробовать наш Cloudflare Worker если он сконфигурирован
            if (workerDomain.isNotEmpty() && targetIp != null) {
                try {
                    val path = "/apiws?dst=$targetIp&dc=$dc&media=${if (isMedia) 1 else 0}"
                    ws = RawWebSocket.connect(workerDomain, workerDomain, path = path)
                    Log.i(TAG, "[$label] CF worker connected for DC$dc")
                } catch (e: Exception) {
                    Log.w(TAG, "[$label] CF worker failed: ${e.message}")
                }
            }

            // Прямое подключение к kws<N>.web.telegram.org
            if (ws == null && targetIp != null) {
                for (domain in domains) {
                    try {
                        ws = RawWebSocket.connect(targetIp, domain)
                        Log.i(TAG, "[$label] WS connected DC$dc via $targetIp ($domain)")
                        break
                    } catch (e: WsHandshakeError) {
                        Log.w(TAG, "[$label] WS handshake $domain: ${e.statusLine}")
                    } catch (e: Exception) {
                        Log.w(TAG, "[$label] WS connect $domain: ${e.message}")
                    }
                }
            }

            if (ws == null) {
                Log.w(TAG, "[$label] no working transport for DC$dc")
                return
            }

            // 6. Отправляем relay_init, запускаем бридж
            ws.send(relayInit)
            bridgeWsReencrypt(clientIn, clientOut, ws, ctx, label)
        } catch (e: EOFException) {
            Log.d(TAG, "[$label] client EOF")
        } catch (e: Exception) {
            Log.w(TAG, "[$label] error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    /** @return (dc, isMedia, protoTag, decPrekeyIv) либо null */
    private fun tryHandshake(handshake: ByteArray, secret: ByteArray):
        Quadruple<Int, Boolean, ByteArray, ByteArray>? {
        val decPrekeyAndIv = handshake.copyOfRange(TgProto.SKIP_LEN,
            TgProto.SKIP_LEN + TgProto.PREKEY_LEN + TgProto.IV_LEN)
        val decPrekey = decPrekeyAndIv.copyOfRange(0, TgProto.PREKEY_LEN)
        val decIv = decPrekeyAndIv.copyOfRange(TgProto.PREKEY_LEN, TgProto.PREKEY_LEN + TgProto.IV_LEN)

        val decKey = sha256(decPrekey + secret)
        // Python хочет big-endian iv, конвертация: dec_iv int от bytes 'big' -> bytes 'big' (16 байт) =
        // тот же массив. У нас он уже 16 байт, оставляем.
        val decryptor = AesCtrStream(decKey, decIv)
        val decrypted = decryptor.update(handshake)

        val protoTag = decrypted.copyOfRange(TgProto.PROTO_TAG_POS, TgProto.PROTO_TAG_POS + 4)
        val isAbridged = protoTag.contentEquals(TgProto.PROTO_TAG_ABRIDGED)
        val isIntermediate = protoTag.contentEquals(TgProto.PROTO_TAG_INTERMEDIATE)
        val isSecure = protoTag.contentEquals(TgProto.PROTO_TAG_SECURE)
        if (!isAbridged && !isIntermediate && !isSecure) return null

        // dc_idx — little-endian signed 16-bit на позиции 60..62
        val dcLow = decrypted[TgProto.DC_IDX_POS].toInt() and 0xFF
        val dcHigh = decrypted[TgProto.DC_IDX_POS + 1].toInt()
        val dcIdx = ((dcHigh shl 8) or dcLow).toShort().toInt()
        val dcId = kotlin.math.abs(dcIdx)
        val isMedia = dcIdx < 0
        return Quadruple(dcId, isMedia, protoTag, decPrekeyAndIv)
    }

    /** Генерирует relay_init: рандомный 64б c вставленным proto_tag+dc_idx в зашифрованном виде. */
    private fun generateRelayInit(protoTag: ByteArray, dcIdx: Int): ByteArray {
        val rnd = SecureRandom()
        val out = ByteArray(TgProto.HANDSHAKE_LEN)
        while (true) {
            rnd.nextBytes(out)
            if (out[0] in TgProto.RESERVED_FIRST_BYTES) continue
            var clash = false
            for (start in TgProto.RESERVED_STARTS) {
                if (out.copyOfRange(0, 4).contentEquals(start)) { clash = true; break }
            }
            if (clash) continue
            if (out.copyOfRange(4, 8).contentEquals(TgProto.RESERVED_CONTINUE)) continue
            break
        }

        val encKey = out.copyOfRange(TgProto.SKIP_LEN, TgProto.SKIP_LEN + TgProto.PREKEY_LEN)
        val encIv = out.copyOfRange(TgProto.SKIP_LEN + TgProto.PREKEY_LEN,
            TgProto.SKIP_LEN + TgProto.PREKEY_LEN + TgProto.IV_LEN)
        val enc = AesCtrStream(encKey, encIv)

        // dc_bytes — little-endian signed short
        val dcBytes = byteArrayOf((dcIdx and 0xFF).toByte(), ((dcIdx ushr 8) and 0xFF).toByte())
        val randomPad = ByteArray(2).also { rnd.nextBytes(it) }
        val tailPlain = protoTag + dcBytes + randomPad

        // Encrypt full block, потом извлечь keystream для последних 8 байт.
        val encryptedFull = enc.update(out)
        val keystreamTail = ByteArray(8)
        for (i in 0 until 8) {
            keystreamTail[i] = (encryptedFull[56 + i].toInt() xor out[56 + i].toInt()).toByte()
        }
        val encryptedTail = ByteArray(8)
        for (i in 0 until 8) {
            encryptedTail[i] = (tailPlain[i].toInt() xor keystreamTail[i].toInt()).toByte()
        }

        val result = out.copyOf()
        System.arraycopy(encryptedTail, 0, result, TgProto.PROTO_TAG_POS, 8)
        return result
    }

    /** Шифры для re-encryption: client ↔ TG. */
    private data class CryptoCtx(
        val cltDec: AesCtrStream,
        val cltEnc: AesCtrStream,
        val tgEnc: AesCtrStream,
        val tgDec: AesCtrStream,
    )

    private fun buildCryptoCtx(clientDecPrekeyIv: ByteArray, secret: ByteArray,
                                 relayInit: ByteArray): CryptoCtx {
        // client decrypt: key = SHA256(prekey + secret), iv = последние 16
        val cltDecPrekey = clientDecPrekeyIv.copyOfRange(0, TgProto.PREKEY_LEN)
        val cltDecIv = clientDecPrekeyIv.copyOfRange(TgProto.PREKEY_LEN,
            TgProto.PREKEY_LEN + TgProto.IV_LEN)
        val cltDecKey = sha256(cltDecPrekey + secret)

        // client encrypt: prekey+iv reversed (как [::-1] в Python)
        val cltEncPrekeyIv = clientDecPrekeyIv.reversedArray()
        val cltEncKey = sha256(cltEncPrekeyIv.copyOfRange(0, TgProto.PREKEY_LEN) + secret)
        val cltEncIv = cltEncPrekeyIv.copyOfRange(TgProto.PREKEY_LEN, TgProto.PREKEY_LEN + TgProto.IV_LEN)

        val cltDecryptor = AesCtrStream(cltDecKey, cltDecIv)
        val cltEncryptor = AesCtrStream(cltEncKey, cltEncIv)
        cltDecryptor.update(TgProto.ZERO_64)   // fast-forward 64 байта

        // relay side
        val relayEncKey = relayInit.copyOfRange(TgProto.SKIP_LEN, TgProto.SKIP_LEN + TgProto.PREKEY_LEN)
        val relayEncIv = relayInit.copyOfRange(TgProto.SKIP_LEN + TgProto.PREKEY_LEN,
            TgProto.SKIP_LEN + TgProto.PREKEY_LEN + TgProto.IV_LEN)

        val relayDecRev = relayInit.copyOfRange(TgProto.SKIP_LEN,
            TgProto.SKIP_LEN + TgProto.PREKEY_LEN + TgProto.IV_LEN).reversedArray()
        val relayDecKey = relayDecRev.copyOfRange(0, TgProto.KEY_LEN)
        val relayDecIv = relayDecRev.copyOfRange(TgProto.KEY_LEN, TgProto.KEY_LEN + TgProto.IV_LEN)

        val tgEncryptor = AesCtrStream(relayEncKey, relayEncIv)
        val tgDecryptor = AesCtrStream(relayDecKey, relayDecIv)
        tgEncryptor.update(TgProto.ZERO_64)

        return CryptoCtx(cltDecryptor, cltEncryptor, tgEncryptor, tgDecryptor)
    }

    /** Двунаправленный bridge: TCP-client ↔ TG-WS с пере-шифрованием. */
    private fun bridgeWsReencrypt(clientIn: Any, clientOut: Any, ws: RawWebSocket,
                                    ctx: CryptoCtx, label: String) {
        val readChunk: (Int) -> ByteArray = when (clientIn) {
            is FakeTlsStream -> { n -> clientIn.read(n) }
            is java.io.InputStream -> { n ->
                val buf = ByteArray(n)
                val r = clientIn.read(buf)
                if (r <= 0) ByteArray(0) else buf.copyOfRange(0, r)
            }
            else -> throw IllegalStateException("unsupported stream type")
        }
        val writeBytes: (ByteArray) -> Unit = when (clientOut) {
            is FakeTlsStream -> { b -> clientOut.write(b); clientOut.flush() }
            is java.io.OutputStream -> { b -> clientOut.write(b); clientOut.flush() }
            else -> throw IllegalStateException("unsupported stream type")
        }

        // tcp_to_ws goroutine
        val upJob = scope.launch {
            try {
                while (isActive && !ws.isClosed()) {
                    val chunk = readChunk(65536)
                    if (chunk.isEmpty()) break
                    val plain = ctx.cltDec.update(chunk)
                    val reenc = ctx.tgEnc.update(plain)
                    ws.send(reenc)
                }
            } catch (e: Exception) {
                Log.d(TAG, "[$label] tcp->ws ended: ${e.message}")
            }
        }

        // ws_to_tcp loop (blocking)
        try {
            while (!ws.isClosed()) {
                val data = ws.recv() ?: break
                if (data.isEmpty()) continue
                val plain = ctx.tgDec.update(data)
                val reenc = ctx.cltEnc.update(plain)
                writeBytes(reenc)
            }
        } catch (e: Exception) {
            Log.d(TAG, "[$label] ws->tcp ended: ${e.message}")
        } finally {
            upJob.cancel()
            try { ws.close() } catch (_: Exception) {}
        }
    }

    private fun wsDomains(dc: Int, isMedia: Boolean): List<String> {
        val effectiveDc = if (dc == 203) 2 else dc
        return if (isMedia) {
            listOf("kws$effectiveDc-1.web.telegram.org", "kws$effectiveDc.web.telegram.org")
        } else {
            listOf("kws$effectiveDc.web.telegram.org", "kws$effectiveDc-1.web.telegram.org")
        }
    }

    companion object {
        private const val TAG = "TgProxyEngine"

        fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it) }

        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.lowercase().trim()
            val out = ByteArray(clean.length / 2)
            for (i in out.indices) {
                out[i] = ((Character.digit(clean[i * 2], 16) shl 4)
                    or Character.digit(clean[i * 2 + 1], 16)).toByte()
            }
            return out
        }

        /** Сгенерировать случайный 32-символьный hex-секрет для нового деплоя. */
        fun generateSecret(): String {
            val b = ByteArray(16)
            SecureRandom().nextBytes(b)
            return b.toHex()
        }
    }
}

/** Аналог Pair/Triple для 4 значений. */
data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
