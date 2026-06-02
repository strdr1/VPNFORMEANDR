package com.amsales.vpn.tgproxy

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Поточный AES-256-CTR — обёртка над javax.crypto.Cipher.
 * В Python это cryptography.hazmat AES + CTR + .update(buf).
 *
 * AES/CTR/NoPadding в JCE поддерживает потоковую обработку (chunk-by-chunk).
 */
class AesCtrStream(key: ByteArray, iv: ByteArray) {
    private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    /** Преобразовать chunk и вернуть результат той же длины. */
    fun update(input: ByteArray): ByteArray = cipher.update(input) ?: ByteArray(0)

    fun update(input: ByteArray, off: Int, len: Int): ByteArray =
        cipher.update(input, off, len) ?: ByteArray(0)
}
