package com.beam.app.protocol.transfer

import com.beam.app.protocol.ChunkHeader
import kotlinx.coroutines.channels.Channel
import java.io.InputStream
import java.util.UUID

class ChunkSender(
    private val metadata: FileMetadata,
    private val openStream: () -> InputStream,
    private val wire: TransferWire,
    private val window: Int = ChunkPlan.DEFAULT_WINDOW_CHUNKS,
) {
    private val plan = ChunkPlan(metadata.sizeBytes, metadata.chunkSize)
    private val transferUuid = UUID.fromString(metadata.transferId)
    private val acked = RangeTracker()

    private val windowReleased = Channel<Unit>(Channel.CONFLATED)
    private var sentCount = 0L

    var bytesSent = 0L
        private set

    suspend fun run(): Long {
        wire.sendStart(
            TransferStartBody(
                transferId = metadata.transferId,
                chunkSize = metadata.chunkSize,
                chunkCount = plan.chunkCount,
                startIndex = 0,
            ),
        )
        val chunkBuffer = ByteArray(metadata.chunkSize)
        openStream().use { stream ->
            for (index in 0L until plan.chunkCount) {
                awaitWindowSlot()
                val length = plan.length(index).toInt()
                readFully(stream, chunkBuffer, length)
                val payload = if (length == chunkBuffer.size) chunkBuffer else chunkBuffer.copyOf(length)
                wire.sendChunk(ChunkHeader(transferUuid, index, length), payload)
                sentCount++
                bytesSent += length
            }
        }
        wire.sendEnd(TransferEndBody(metadata.transferId, bytesSent))
        return bytesSent
    }

    /** Applies a CHUNK_ACK from the receiver
     *
     * Idempotent. */
    suspend fun onAck(body: ChunkAckBody) {
        if (body.transferId != metadata.transferId) {
            throw TransferProtocolException("ACK for another transfer: ${body.transferId}")
        }
        val newlyAcked = acked.addAll(body.received.map { it.toLongRange() })
        if (newlyAcked > 0) windowReleased.trySend(Unit)
    }

    private suspend fun awaitWindowSlot() {
        while (sentCount - acked.receivedCount >= window) {
            windowReleased.receive()
        }
    }

    private fun readFully(
        stream: InputStream,
        buffer: ByteArray,
        length: Int,
    ) {
        var filled = 0
        while (filled < length) {
            val read = stream.read(buffer, filled, length - filled)
            if (read < 0) throw TransferStorageException("File ended before chunk data ($filled/$length bytes)")
            filled += read
        }
    }
}
