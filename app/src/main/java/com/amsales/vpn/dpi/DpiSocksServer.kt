package com.amsales.vpn.dpi

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
class DpiSocksServer(private val port: Int) {

    @Volatile var running: Boolean = false
        private set

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var acceptJob: Job? = null

    fun start() {
        if (running) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("127.0.0.1", port))
        serverSocket = ss
        running = true

        acceptJob = scope.launch {
            Log.i(TAG, "DPI SOCKS5 listening on 127.0.0.1:$port")
            while (isActive && running) {
                try {
                    val client = withContext(Dispatchers.IO) { ss.accept() }
                    client.tcpNoDelay = true
                    scope.launch { handle(client) }
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "accept: ${e.message}")
                    break
                }
            }
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

            // 1. Greeting: VER(1) NMETHODS(1) METHODS(N)
            val ver = cin.read()
            if (ver != 0x05) throw IOException("not socks5: $ver")
            val nm = cin.read()
            val methods = ByteArray(nm)
            cin.readFully(methods)
            // 2. Метод: NO_AUTH (0x00)
            cout.write(byteArrayOf(0x05, 0x00)); cout.flush()

            // 3. Request: VER(1) CMD(1) RSV(1) ATYP(1) DST.ADDR DST.PORT(2)
            val v2 = cin.read()
            val cmd = cin.read()
            cin.read()  // RSV
            val atyp = cin.read()
            if (v2 != 0x05 || cmd != 0x01) {
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

            // 4. Подключаемся к destination
            val upstream = Socket()
            try {
                upstream.connect(InetSocketAddress(host, port), 10000)
                upstream.tcpNoDelay = true
            } catch (e: Exception) {
                Log.w(TAG, "upstream connect $host:$port failed: ${e.message}")
                reply(cout, 0x05)  // connection refused
                return
            }

            // 5. Reply success: VER(1) REP(1) RSV(1) ATYP(1) BND.ADDR BND.PORT(2)
            cout.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            cout.flush()

            // 6. Bridge с фрагментацией первого исходящего пакета.
            val uin = upstream.getInputStream()
            val uout = upstream.getOutputStream()

            // client -> upstream (с фрагментацией первого пакета).
            // Sing-box может слать TLS Client Hello несколькими write'ами —
            // собираем их в один буфер пока он не выглядит как полный TLS-record,
            // потом фрагментируем.
            val upJob = scope.launch {
                try {
                    val buf = ByteArray(16384)
                    var fragmented = false
                    val firstChunk = java.io.ByteArrayOutputStream()
                    val firstReadDeadline = System.currentTimeMillis() + 300

                    while (isActive) {
                        val n = cin.read(buf)
                        if (n <= 0) break

                        if (!fragmented) {
                            firstChunk.write(buf, 0, n)
                            val arr = firstChunk.toByteArray()
                            // Ждём весь TLS Client Hello: 5-байт header содержит длину
                            val complete = isTlsRecordComplete(arr)
                                || System.currentTimeMillis() > firstReadDeadline
                                || firstChunk.size() > 8192
                            if (complete) {
                                TlsFragmenter.sendWithFragmentation(upstream, uout, arr)
                                fragmented = true
                            }
                        } else {
                            uout.write(buf, 0, n); uout.flush()
                        }
                    }
                    // Если соединение закрылось до завершения сборки — шлём что есть
                    if (!fragmented && firstChunk.size() > 0) {
                        TlsFragmenter.sendWithFragmentation(upstream, uout, firstChunk.toByteArray())
                    }
                } catch (e: Exception) {
                    // connection-reset нормально при close
                } finally {
                    try { upstream.shutdownOutput() } catch (_: Exception) {}
                }
            }

            // upstream -> client (как есть)
            try {
                val buf = ByteArray(16384)
                while (true) {
                    val n = uin.read(buf)
                    if (n <= 0) break
                    cout.write(buf, 0, n); cout.flush()
                }
            } catch (_: Exception) {}

            upJob.cancel()
            try { upstream.close() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.d(TAG, "client error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
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
