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
    private val onProgress: () -> Unit = {},
) {
    private val plan = ChunkPlan(metadata.sizeBytes, metadata.chunkSize)
    private val transferUuid = UUID.fromString(metadata.transferId)
    private val ranges = RangeTracker()
    private val sidecar = RangesSidecar(tempDir, metadata.transferId)

    val state = TransferStateMachine(TransferPhase.Accepted)

    val partFile: File
        get() = File(tempDir, "${metadata.transferId}.part")

    val receivedRanges: List<LongRange>
        get() = ranges.coalescedRanges()

    val bytesReceived: Long
        get() = plan.bytesIn(ranges.coalescedRanges())

    private var inactivityPaused = false

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
        if (state.phase != TransferPhase.Transferring && !(state.phase == TransferPhase.Paused && inactivityPaused)) {
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
            if (inactivityPaused) {
                transition(TransferEvent.LinkRestored)
                inactivityPaused = false
            }
            onProgress()
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
            state.on(TransferEvent.FatalError(TransferError(TransferErrorCode.INTERNAL_ERROR)))
            sidecar.delete()
            ReceiveEndStatus.INCOMPLETE
        }
    }

    suspend fun cancel(error: TransferError) {
        if (state.isTerminal) return
        val transitioned =
            try {
                state.on(TransferEvent.CancelRequested(error))
                true
            } catch (e: IllegalTransferTransition) {
                false
            }
        if (!transitioned) return
        abandon()
        partFile.delete()
        sidecar.delete()
        runCatching {
            wire.sendCancel(TransferCancelBody(metadata.transferId, error.code, error.detail.ifBlank { null }))
        }
    }

    suspend fun terminate(error: TransferError) {
        if (state.isTerminal) return
        if (!transition(TransferEvent.FatalError(error))) return
        abandon()
        partFile.delete()
        sidecar.delete()
    }

    /** Applies TRANSFER_CANCEL from the peer. */
    suspend fun onPeerCancel(body: TransferCancelBody) {
        if (body.transferId != metadata.transferId) {
            throw TransferProtocolException("CANCEL for another transfer: ${body.transferId}")
        }
        cancel(TransferError(body.code, body.detail ?: ""))
    }

    /** Applies TRANSFER_ERROR from the peer. */
    suspend fun onPeerError(body: TransferErrorBody) {
        if (body.transferId != metadata.transferId) {
            throw TransferProtocolException("ERROR for another transfer: ${body.transferId}")
        }
        if (state.isTerminal) return
        val transitioned =
            try {
                state.on(TransferEvent.FatalError(TransferError(body.code, body.detail ?: "")))
                true
            } catch (e: IllegalTransferTransition) {
                false
            }
        if (!transitioned) return
        abandon()
        partFile.delete()
        sidecar.delete()
    }

    fun onInactivityExpired() {
        if (state.phase is TransferPhase.Transferring) {
            inactivityPaused = transition(TransferEvent.LinkLost)
        }
    }

    suspend fun onLinkLost() {
        inactivityPaused = false
        if (state.phase is TransferPhase.Transferring) transition(TransferEvent.LinkLost)
    }

    suspend fun onLinkRestored() {
        if (state.phase is TransferPhase.Paused) transition(TransferEvent.LinkRestored)
    }

    suspend fun onReconnectWindowExpired() {
        if (state.isTerminal) return
        val detail = "Link lost and the reconnect window elapsed"
        val failed = transition(TransferEvent.FatalError(TransferError(TransferErrorCode.CONNECTION_LOST, detail)))
        if (!failed) return
        abandon()
        partFile.delete()
        sidecar.delete()
    }

    suspend fun onAcceptToStartExpired() {
        if (state.phase !is TransferPhase.Accepted) return
        val detail = "No TRANSFER_START within the accept-to-start window"
        val failed = transition(TransferEvent.FatalError(TransferError(TransferErrorCode.TRANSFER_TIMEOUT, detail)))
        if (!failed) return
        abandon()
        partFile.delete()
        sidecar.delete()
        runCatching {
            wire.sendError(TransferErrorBody(metadata.transferId, TransferErrorCode.TRANSFER_TIMEOUT, detail))
        }
    }

    suspend fun onLocalFailure(
        code: TransferErrorCode,
        detail: String,
    ) {
        if (state.isTerminal) return
        val failed = transition(TransferEvent.FatalError(TransferError(code, detail)))
        if (!failed) return
        abandon()
        partFile.delete()
        sidecar.delete()
        runCatching {
            wire.sendError(TransferErrorBody(metadata.transferId, code, detail))
        }
    }

    /** Releases the temp file without deleting it. */
    fun abandon() {
        runCatching { file?.syncAndClose() }
        file = null
    }

    private suspend fun sendAck() {
        sidecar.flush(ranges.coalescedRanges())
        val body =
            ChunkAckBody(
                transferId = metadata.transferId,
                received = ranges.coalescedRanges().map { IndexRange.of(it) },
                highestContiguous = ranges.highestContiguous,
            )
        wire.sendAck(body)
        newSinceAck = 0
    }

    private fun transition(event: TransferEvent): Boolean =
        try {
            state.on(event)
            true
        } catch (e: IllegalTransferTransition) {
            false
        }

    private fun RandomAccessFile.syncAndClose() {
        fd.sync()
        close()
    }
}
