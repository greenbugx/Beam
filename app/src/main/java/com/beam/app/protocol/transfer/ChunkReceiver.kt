package com.beam.app.protocol.transfer

import com.beam.app.protocol.ChunkHeader
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

enum class ReceiveEndStatus {
    READY_TO_VERIFY,

    INCOMPLETE,
}

class ChunkReceiver(
    private val metadata: FileMetadata,
    private val tempDir: File,
    private val wire: TransferWire,
) {
    private val plan = ChunkPlan(metadata.sizeBytes, metadata.chunkSize)
    private val transferUuid = UUID.fromString(metadata.transferId)
    private val ranges = RangeTracker()

    val state = TransferStateMachine(TransferPhase.Accepted)

    val partFile: File
        get() = File(tempDir, "${metadata.transferId}.part")

    val receivedRanges: List<LongRange>
        get() = ranges.coalescedRanges()

    private var file: RandomAccessFile? = null
    private var newSinceAck = 0

    fun onStart(body: TransferStartBody) {
        when (state.phase) {
            is TransferPhase.Transferring -> {
                if (body.chunkSize != metadata.chunkSize || body.chunkCount != plan.chunkCount) {
                    throw TransferProtocolException("Duplicate TRANSFER_START with different geometry")
                }
                return
            }

            is TransferPhase.Accepted -> {
                Unit
            }

            else -> {
                throw TransferProtocolException("TRANSFER_START in phase ${state.phase::class.simpleName}")
            }
        }
        if (body.chunkSize != metadata.chunkSize || body.chunkCount != plan.chunkCount) {
            throw TransferProtocolException("TRANSFER_START geometry differs from offered metadata")
        }
        if (body.startIndex != 0L) {
            throw TransferProtocolException("startIndex ${body.startIndex} unsupported in M3 (resume is FUTURE)")
        }
        try {
            tempDir.mkdirs()
            val opened = RandomAccessFile(partFile, "rw")
            opened.setLength(metadata.sizeBytes)
            file = opened
        } catch (e: IOException) {
            throw TransferStorageException("Cannot pre-size temp file $partFile", e)
        }
        state.on(TransferEvent.TransferStarted)
    }

    /** Applies CHUNK_DATA: validates, writes at its offset, records the range, maybe ACKs. */
    suspend fun onChunk(
        header: ChunkHeader,
        payload: ByteArray,
    ) {
        if (state.phase != TransferPhase.Transferring) {
            throw TransferProtocolException("CHUNK_DATA in phase ${state.phase::class.simpleName}")
        }
        if (header.transferId != transferUuid) {
            throw TransferProtocolException("Chunk for another transfer: $header")
        }
        if (header.chunkIndex >= plan.chunkCount) {
            throw TransferProtocolException("chunkIndex ${header.chunkIndex} beyond chunkCount ${plan.chunkCount}")
        }
        if (header.chunkLength != payload.size || header.chunkLength.toLong() != plan.length(header.chunkIndex)) {
            throw TransferProtocolException("Chunk ${header.chunkIndex} length mismatch")
        }
        val sink = file ?: throw TransferProtocolException("CHUNK_DATA before TRANSFER_START")
        try {
            sink.seek(plan.offset(header.chunkIndex))
            sink.write(payload)
        } catch (e: IOException) {
            throw TransferStorageException("Write failed at chunk ${header.chunkIndex}", e)
        }
        if (ranges.add(header.chunkIndex)) {
            newSinceAck++
            if (newSinceAck >= ChunkPlan.ACK_EVERY_N_CHUNKS) {
                sendAck()
            }
        }
    }

    suspend fun onEnd(body: TransferEndBody): ReceiveEndStatus {
        if (state.phase == TransferPhase.Verifying) return ReceiveEndStatus.READY_TO_VERIFY
        if (state.phase != TransferPhase.Transferring) {
            throw TransferProtocolException("TRANSFER_END in phase ${state.phase::class.simpleName}")
        }
        sendAck()
        try {
            file?.syncAndClose()
        } catch (e: IOException) {
            throw TransferStorageException("Close failed for $partFile", e)
        }
        return if (ranges.isComplete(plan.chunkCount)) {
            state.on(TransferEvent.TransferEnded)
            ReceiveEndStatus.READY_TO_VERIFY
        } else {
            state.on(TransferEvent.FatalError)
            ReceiveEndStatus.INCOMPLETE
        }
    }

    /** Releases the temp file without deleting it. */
    fun abandon() {
        runCatching { file?.syncAndClose() }
        file = null
    }

    private suspend fun sendAck() {
        val body =
            ChunkAckBody(
                transferId = metadata.transferId,
                received = ranges.coalescedRanges().map { IndexRange.of(it) },
                highestContiguous = ranges.highestContiguous,
            )
        wire.sendAck(body)
        newSinceAck = 0
    }

    private fun RandomAccessFile.syncAndClose() {
        fd.sync()
        close()
    }
}
