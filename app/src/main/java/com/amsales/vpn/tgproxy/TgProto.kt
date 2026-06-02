package com.amsales.vpn.tgproxy

/**
 * MTProto Telegram WS-Bridge — константы протокола.
 * Портировано с tg-ws-proxy/proxy/utils.py
 */
object TgProto {
    const val HANDSHAKE_LEN = 64
    const val SKIP_LEN = 8
    const val PREKEY_LEN = 32
    const val KEY_LEN = 32
    const val IV_LEN = 16
    const val PROTO_TAG_POS = 56
    const val DC_IDX_POS = 60

    val PROTO_TAG_ABRIDGED = byteArrayOf(0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte(), 0xEF.toByte())
    val PROTO_TAG_INTERMEDIATE = byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte())
    val PROTO_TAG_SECURE = byteArrayOf(0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte())

    const val PROTO_ABRIDGED_INT = 0xEFEFEFEF.toInt()
    const val PROTO_INTERMEDIATE_INT = 0xEEEEEEEE.toInt()
    const val PROTO_PADDED_INTERMEDIATE_INT = 0xDDDDDDDD.toInt()

    val ZERO_64 = ByteArray(64)

    val RESERVED_FIRST_BYTES = setOf(0xEF.toByte())
    val RESERVED_STARTS = listOf(
        byteArrayOf(0x48, 0x45, 0x41, 0x44),  // HEAD
        byteArrayOf(0x50, 0x4F, 0x53, 0x54),  // POST
        byteArrayOf(0x47, 0x45, 0x54, 0x20),  // "GET "
        byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte()),
        byteArrayOf(0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte()),
        byteArrayOf(0x16, 0x03, 0x01, 0x02),
    )
    val RESERVED_CONTINUE = byteArrayOf(0, 0, 0, 0)

    /** Дефолтные IP датацентров Telegram. */
    val DC_DEFAULT_IPS = mapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.51",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "149.154.171.5",
        203 to "91.105.192.100",
    )

    /** Редиректы DC при работе через наш сервер. */
    val DC_REDIRECTS = mapOf(
        2 to "149.154.167.220",
        4 to "149.154.167.220",
    )

    fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    fun ByteArray.contentEqualsAt(off: Int, other: ByteArray): Boolean {
        if (size < off + other.size) return false
        for (i in other.indices) if (this[off + i] != other[i]) return false
        return true
    }
}
