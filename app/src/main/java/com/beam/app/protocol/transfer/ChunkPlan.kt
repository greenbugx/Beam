package com.beam.app.protocol.transfer

import kotlin.math.min

class ChunkPlan(
    val sizeBytes: Long,
    val chunkSize: Int,
) {
    val chunkCount: Long = FileMetadata.derivedChunkCount(sizeBytes, chunkSize)

    /** Byte offset of chunk [index] in the file. */
    fun offset(index: Long): Long = index * chunkSize

    /** Wire length of chunk [index];
     *
     * 0 only for the single empty chunk. */
    fun length(index: Long): Long {
        require(index in 0 until chunkCount) { "chunkIndex $index outside 0 until $chunkCount" }
        if (sizeBytes == 0L) return 0L
        return min(chunkSize.toLong(), sizeBytes - offset(index))
    }

    fun isLastChunk(index: Long): Boolean = index == chunkCount - 1

    /**
     * Computed from run lengths so a 16 384-chunk range set costs a few
     * iterations, not 16 384.
     */
    fun bytesIn(ranges: List<LongRange>): Long {
        var total = 0L
        for (range in ranges) {
            val first = range.first.coerceIn(0, chunkCount - 1)
            val last = range.last.coerceIn(0, chunkCount - 1)
            if (last < first) continue
            total += (last - first + 1) * chunkSize
            // The final chunk is partial, so its length is not chunkSize.
            if (last == chunkCount - 1) total -= (chunkSize - length(chunkCount - 1))
        }
        return total
    }

    companion object {
        /** 8 chunks = 2 MiB at 256 KiB. */
        const val DEFAULT_WINDOW_CHUNKS = 8

        /** every 4 new chunks, plus on END. */
        const val ACK_EVERY_N_CHUNKS = 4
    }
}
