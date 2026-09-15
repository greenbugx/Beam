package com.beam.app.protocol.transfer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.ceil

enum class MetadataError {
    SIZE_NEGATIVE,
    SIZE_TOO_LARGE,
    CHUNK_SIZE_TOO_SMALL,
    CHUNK_SIZE_TOO_LARGE,
    CHUNK_COUNT_MISMATCH,
    CHUNK_COUNT_TOO_LARGE,
    NAME_INVALID,
    SHA256_MALFORMED,
    MIME_TOO_LONG,
    TRANSFER_ID_INVALID,
    FILE_ID_INVALID,
}

@Serializable
data class FileMetadata(
    @SerialName("transferId") val transferId: String,
    @SerialName("fileId") val fileId: String,
    @SerialName("name") val name: String,
    @SerialName("mime") val mime: String,
    @SerialName("sizeBytes") val sizeBytes: Long,
    @SerialName("sha256") val sha256: String,
    @SerialName("chunkSize") val chunkSize: Int,
    @SerialName("chunkCount") val chunkCount: Long,
    @SerialName("modifiedAt") val modifiedAt: Long? = null,
) {
    val derivedChunkCount: Long
        get() = derivedChunkCount(sizeBytes, chunkSize)

    val lastChunkLength: Long
        get() = if (sizeBytes == 0L) 0L else sizeBytes - (derivedChunkCount - 1) * chunkSize

    fun offsetOf(chunkIndex: Long): Long = chunkIndex * chunkSize

    /** length of a chunk is derived from the file size;
     *
     * the last chunk is partial. */
    fun chunkLengthAt(chunkIndex: Long): Long =
        when {
            chunkIndex !in 0L until derivedChunkCount -> 0L
            chunkIndex < derivedChunkCount - 1 -> chunkSize.toLong()
            else -> lastChunkLength
        }

    fun validate(): List<MetadataError> {
        val errors = mutableListOf<MetadataError>()
        if (sizeBytes < 0) errors += MetadataError.SIZE_NEGATIVE
        if (sizeBytes > MAX_FILE_SIZE_BYTES) errors += MetadataError.SIZE_TOO_LARGE
        when {
            chunkSize < MIN_CHUNK_SIZE_BYTES -> errors += MetadataError.CHUNK_SIZE_TOO_SMALL
            chunkSize > MAX_CHUNK_SIZE_BYTES -> errors += MetadataError.CHUNK_SIZE_TOO_LARGE
        }
        if (chunkCount > MAX_CHUNK_COUNT) errors += MetadataError.CHUNK_COUNT_TOO_LARGE
        if (chunkCount != derivedChunkCount) errors += MetadataError.CHUNK_COUNT_MISMATCH
        if (FilenameSanitizer.sanitize(name).isBlank() || FilenameSanitizer.sanitize(name).length > MAX_NAME_CHARS) {
            errors += MetadataError.NAME_INVALID
        }
        if (!SHA256_REGEX.matches(sha256)) errors += MetadataError.SHA256_MALFORMED
        if (mime.length > MAX_MIME_CHARS) errors += MetadataError.MIME_TOO_LONG
        if (transferId.isBlank()) errors += MetadataError.TRANSFER_ID_INVALID
        if (fileId.isBlank()) errors += MetadataError.FILE_ID_INVALID
        return errors
    }

    companion object {
        /** Default chunk size. */
        const val DEFAULT_CHUNK_SIZE_BYTES = 256 * 1024

        /** Smallest legal chunk. */
        const val MIN_CHUNK_SIZE_BYTES = 64 * 1024

        /** Largest legal chunk. */
        const val MAX_CHUNK_SIZE_BYTES = 1024 * 1024

        /** Max file size default,
         *
         * configurable at the policy layer. */
        const val MAX_FILE_SIZE_BYTES: Long = 10L * 1024 * 1024 * 1024

        /** Hard chunk-count ceiling. */
        const val MAX_CHUNK_COUNT: Long = 1L shl 20

        /** 64-char lowercase hex. */
        private val SHA256_REGEX = Regex("[0-9a-f]{64}")

        private const val MAX_MIME_CHARS = 127
        private const val MAX_NAME_CHARS = 255

        fun derivedChunkCount(
            sizeBytes: Long,
            chunkSize: Int,
        ): Long = if (sizeBytes == 0L) 1 else (sizeBytes - 1) / chunkSize + 1
    }
}
