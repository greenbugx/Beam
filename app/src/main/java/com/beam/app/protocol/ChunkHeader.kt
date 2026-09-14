package com.beam.app.protocol

import java.nio.ByteBuffer
import java.util.UUID

data class ChunkHeader(
    val transferId: UUID,
    /** Zero-based position of this chunk within the transfer. */
    val chunkIndex: Long,
    /** Length of the chunk bytes that follow this header. */
    val chunkLength: Int,
) {
    init {
        require(chunkIndex >= 0) { "chunkIndex must be non-negative, was $chunkIndex" }
        require(chunkLength >= 0) { "chunkLength must be non-negative, was $chunkLength" }
    }

    companion object {
        const val SIZE = 28

        fun decode(headerBytes: ByteArray): ChunkHeader {
            require(headerBytes.size >= SIZE) { "Chunk header needs $SIZE bytes, got ${headerBytes.size}" }
            val buffer = ByteBuffer.wrap(headerBytes)
            val mostSignificant = buffer.long
            val leastSignificant = buffer.long
            val index = buffer.long
            val length = buffer.int
            return ChunkHeader(
                transferId = UUID(mostSignificant, leastSignificant),
                chunkIndex = index,
                chunkLength = length,
            )
        }
    }

    /** Encodes the header into a fresh [SIZE]-byte buffer. */
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(SIZE)
        buffer.putLong(transferId.mostSignificantBits)
        buffer.putLong(transferId.leastSignificantBits)
        buffer.putLong(chunkIndex)
        buffer.putInt(chunkLength)
        return buffer.array()
    }
}
