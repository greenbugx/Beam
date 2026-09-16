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
    private val timeouts: TransferTimeouts = TransferTimeouts(),
) {
    val state = TransferStateMachine(TransferPhase.Offered)

    private val plan = ChunkPlan(metadata.sizeBytes, metadata.chunkSize)
    private val transferUuid = UUID.fromString(metadata.transferId)
    private val acked = RangeTracker()
    private val canceller = TransferCanceller()

    private val windowReleased = Channel<Unit>(Channel.CONFLATED)
    private var sentCount = 0L
    private var inactivityStrikes = 0

    var bytesSent = 0L
        private set

    suspend fun run(): Long {
        try {
            state.on(TransferEvent.FileAccepted)
            state.on(TransferEvent.TransferStarted)
            sendAll()
            state.on(TransferEvent.TransferEnded)
            return bytesSent
        } catch (e: TransferCancelledException) {
            throw e
        } catch (e: TransferTimeoutException) {
            fail(TransferErrorCode.TRANSFER_TIMEOUT, e.message ?: "transfer timed out")
            throw e
        } catch (e: TransferStorageException) {
            fail(TransferErrorCode.INTERNAL_ERROR, e.message ?: "storage failure")
            throw e
        } catch (e: TransferProtocolException) {
            fail(TransferErrorCode.INTERNAL_ERROR, e.message ?: "protocol violation")
            throw e
        } catch (e: Exception) {
            fail(TransferErrorCode.INTERNAL_ERROR, e.message ?: "unexpected failure")
            throw e
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
        canceller.cancel(error)
        windowReleased.trySend(Unit)
        runCatching {
            wire.sendCancel(TransferCancelBody(metadata.transferId, error.code, error.detail.ifBlank { null }))
        }
    }

    /** Applies a CHUNK_ACK from the receiver.
     *
     * Idempotent. */
    suspend fun onAck(body: ChunkAckBody) {
        if (body.transferId != metadata.transferId) {
            throw TransferProtocolException("ACK for another transfer: ${body.transferId}")
        }
        val newlyAcked = acked.addAll(body.received.map { it.toLongRange() })
        if (newlyAcked > 0) windowReleased.trySend(Unit)
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
        fail(body.code, body.detail ?: "peer reported error")
        windowReleased.trySend(Unit)
    }

    suspend fun onVerified(body: TransferVerifiedBody) {
        if (body.transferId != metadata.transferId) {
            throw TransferProtocolException("VERIFIED for another transfer: ${body.transferId}")
        }
        if (state.isTerminal) return
        if (body.sha256 != metadata.sha256) {
            fail(TransferErrorCode.HASH_MISMATCH, "Receiver verified a different digest")
            return
        }
        if (!transition(TransferEvent.HashMatched)) return
        windowReleased.trySend(Unit)
    }

    suspend fun onVerifyFailed(body: VerifyFailedBody) {
        if (body.transferId != metadata.transferId) {
            throw TransferProtocolException("VERIFY_FAILED for another transfer: ${body.transferId}")
        }
        if (state.isTerminal) return
        if (!transition(TransferEvent.HashMismatched)) return
        windowReleased.trySend(Unit)
    }

    /** Applies [event]
     *
     * false when the state table has no such edge or the transfer is terminal. */
    private fun transition(event: TransferEvent): Boolean =
        try {
            state.on(event)
            true
        } catch (e: IllegalTransferTransition) {
            false
        }

    private suspend fun sendAll() {
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
                if (state.phase != TransferPhase.Transferring) {
                    throw TransferCancelledException(TransferError(TransferErrorCode.TRANSFER_CANCELLED))
                }
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
    }

    private suspend fun awaitWindowSlot() {
        while (sentCount - acked.receivedCount >= window) {
            if (state.phase !is TransferPhase.Transferring && state.phase !is TransferPhase.Paused) {
                throw TransferCancelledException(TransferError(TransferErrorCode.TRANSFER_CANCELLED))
            }
            try {
                timeouts.inactivity("No ACK progress within inactivity window") {
                    windowReleased.receive()
                }
            } catch (e: TransferTimeoutException) {
                if (state.phase is TransferPhase.Transferring) {
                    state.on(TransferEvent.LinkLost)
                }
                inactivityStrikes += 1
                if (inactivityStrikes >= timeouts.maxConsecutiveInactivity) {
                    fail(
                        TransferErrorCode.TRANSFER_TIMEOUT,
                        "No ACK progress after $inactivityStrikes inactivity pauses",
                    )
                    throw e
                }
                continue
            }
            if (state.phase is TransferPhase.Paused) {
                state.on(TransferEvent.LinkRestored)
            }
            inactivityStrikes = 0
        }
    }

    /** Marks FAILED and reports TRANSFER_ERROR. */
    private suspend fun fail(
        code: TransferErrorCode,
        detail: String,
    ) {
        if (state.isTerminal) return
        try {
            state.on(TransferEvent.FatalError(TransferError(code, detail)))
        } catch (e: IllegalTransferTransition) {
            return
        }
        runCatching {
            wire.sendError(TransferErrorBody(metadata.transferId, code, detail))
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
