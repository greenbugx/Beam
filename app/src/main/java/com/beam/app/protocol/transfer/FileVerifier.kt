package com.beam.app.protocol.transfer

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Streams SHA-256 over a file or stream in one pass
 */
object FileVerifier {
    private const val BUFFER_SIZE = 64 * 1024
    private const val SHA_256 = "SHA-256"

    /** Hex digest of everything [input] yields; does not close [input]. */
    fun hash(input: InputStream): String {
        val digest = MessageDigest.getInstance(SHA_256)
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    /** Hex digest of [file]'s contents streamed. */
    fun hash(file: File): String = file.inputStream().buffered().use { hash(it) }

    /** Lowercase hex of a digest. */
    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
}
