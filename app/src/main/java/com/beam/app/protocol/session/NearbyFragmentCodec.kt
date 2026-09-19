package com.beam.app.protocol.session

import com.beam.app.protocol.FrameCodec
import java.nio.ByteBuffer

/** Transport-only envelope; both ends must use this version of the BYTES wire format. */
internal object NearbyFragmentCodec {
    const val HEADER_SIZE = 20
    const val DEFAULT_MAX_PAYLOAD = 32 * 1024
    const val MAX_FRAME_SIZE = FrameCodec.MAX_DATA_PAYLOAD + FrameCodec.HEADER_SIZE
    private const val MAGIC = 0x424D4601 // BMF, version 1

    fun fragment(
        frame: ByteArray,
        sequence: Long,
        offset: Int,
        maxPayload: Int,
    ): ByteArray {
        require(maxPayload > HEADER_SIZE)
        require(frame.size in FrameCodec.HEADER_SIZE..MAX_FRAME_SIZE)
        require(sequence >= 0 && offset in frame.indices)
        val count = minOf(frame.size - offset, maxPayload - HEADER_SIZE)
        val bytes = ByteArray(HEADER_SIZE + count)
        ByteBuffer
            .wrap(bytes)
            .putInt(MAGIC)
            .putLong(sequence)
            .putInt(offset)
            .putInt(frame.size)
        frame.copyInto(bytes, HEADER_SIZE, offset, offset + count)
        return bytes
    }

    /** Fixed-size fragments permit bounded coverage bookkeeping without overlap ambiguity. */
    class Reassembler(
        private val maxPayload: Int,
    ) {
        private class Pending(
            val bytes: ByteArray,
            val received: BooleanArray,
            var count: Int = 0,
        )

        private val pending = HashMap<Long, Pending>()
        private var nextSequence = 0L
        private var bufferedBytes = 0
        private val fragmentSize = maxPayload - HEADER_SIZE

        init {
            require(fragmentSize > 0)
        }

        fun accept(
            bytes: ByteArray,
            emit: (ByteArray) -> Unit,
        ) {
            require(bytes.size in (HEADER_SIZE + 1)..maxPayload) { "Invalid fragment size" }
            val header = ByteBuffer.wrap(bytes)
            require(header.int == MAGIC) { "Unsupported fragment envelope" }
            val sequence = header.long
            val offset = header.int
            val total = header.int
            require(sequence >= nextSequence && sequence - nextSequence < MAX_PENDING_FRAMES) {
                "Frame sequence outside receive window"
            }
            require(total in FrameCodec.HEADER_SIZE..MAX_FRAME_SIZE) { "Invalid frame size" }
            require(offset >= 0 && offset < total && offset % fragmentSize == 0) { "Invalid fragment offset" }
            val count = bytes.size - HEADER_SIZE
            require(count == minOf(fragmentSize, total - offset)) { "Inconsistent fragment length" }
            val frame =
                pending[sequence] ?: run {
                    require(total <= MAX_BUFFERED_BYTES - bufferedBytes) { "Reassembly byte budget exceeded" }
                    Pending(ByteArray(total), BooleanArray((total + fragmentSize - 1) / fragmentSize)).also {
                        pending[sequence] = it
                        bufferedBytes += total
                    }
                }
            require(frame.bytes.size == total) { "Inconsistent frame size" }
            val index = offset / fragmentSize
            require(!frame.received[index]) { "Duplicate fragment" }
            bytes.copyInto(frame.bytes, offset, HEADER_SIZE)
            frame.received[index] = true
            frame.count++
            while (true) {
                val ready = pending[nextSequence] ?: break
                if (ready.count != ready.received.size) break
                pending.remove(nextSequence)
                bufferedBytes -= ready.bytes.size
                require(nextSequence < Long.MAX_VALUE) { "Frame sequence exhausted" }
                nextSequence++
                emit(ready.bytes)
            }
        }

        fun clear() {
            pending.clear()
            bufferedBytes = 0
        }

        companion object {
            const val MAX_PENDING_FRAMES = 32
            const val MAX_BUFFERED_BYTES = 8 * 1024 * 1024
        }
    }
}
