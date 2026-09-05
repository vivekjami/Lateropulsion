package com.lateropulsion.core.common

import java.security.MessageDigest

public object Hashing {
    public fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    public fun sha256Hex(bytes: ByteArray): String = toHex(sha256(bytes))

    public fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    public fun fromHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex length must be even" }
        return ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private const val HEX = "0123456789abcdef"

    /** Incremental digest for streaming writers. */
    public class Sha256Stream {
        private val md = MessageDigest.getInstance("SHA-256")
        public fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Unit = md.update(bytes, offset, length)
        public fun digest(): ByteArray = md.digest()
    }
}
