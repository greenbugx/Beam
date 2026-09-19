package com.beam.app.protocol.transfer

import com.beam.app.protocol.ChunkHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID

enum class ReceiveEndStatus {
    READY_TO_VERIFY,
    INCOMPLETE,
}

class ChunkReceiver(
    val metadata: FileMetadata,
    private val tempDir: File,
    private val wire: TransferWire,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowNanos: () -> Long = System::nanoTime,
    private val onProgress: () -> Unit = {},
) {
    private val plan = ChunkPlan(metadata.sizeBytes, metadata.chunkSize)
    private val transferUuid = UUID.fromString(metadata.transferId)
    private val ranges = RangeTracker()
    private val sidecar = RangesSidecar(tempDir, metadata.transferId)

    // Shared with the completer: close/delete must never overlap writes or publication.
    val storageMutex = Mutex()
    val state = TransferStateMachine(TransferPhase.Accepted)

    val partFile: File
        get() = File(tempDir, "${metadata.transferId}.part")

    val receivedRanges: List<LongRange>
        get() = ranges.coalescedRanges()

    var bytesReceived = 0L
        private set

    private var inactivityPaused = false
    private var file: RandomAccessFile? = null
    private var newSinceAck = 0
    private var checkpointBytes = 0L
    private var checkpointNanos = nowNanos()

    suspend fun onStart(body: TransferStartBody) {
        storageMutex.withLock {
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
                withContext(ioDispatcher) {
                    tempDir.mkdirs()
                    val opened = RandomAccessFile(partFile, "rw")
                    file = opened
                    opened.setLength(metadata.sizeBytes)
                }
            } catch (e: Exception) {
                withContext(ioDispatcher + NonCancellable) { closeFile() }
                if (e is CancellationException) throw e
                throw TransferStorageException("Cannot pre-size temp file $partFile", e)
            }
            checkpointNanos = nowNanos()
            state.on(TransferEvent.TransferStarted)
        }
    }

    /** Writes before ACK; checkpoint cadence is independent of ACK cadence. */
    suspend fun onChunk(
        header: ChunkHeader,
        payload: ByteArray,
    ) {
        storageMutex.withLock {
            if (state.phase != TransferPhase.Transferring &&
                !(state.phase == TransferPhase.Paused && inactivityPaused)
            ) {
                throw TransferProtocolException("CHUNK_DATA in phase ${state.phase::class.simpleName}")
            }
            if (header.transferId !=
                transferUuid
            ) {
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
                withContext(ioDispatcher) {
                    sink.seek(plan.offset(header.chunkIndex))
                    sink.write(payload)
                }
            } catch (e: IOException) {
                throw TransferStorageException("Write failed at chunk ${header.chunkIndex}", e)
            }
            if (ranges.add(header.chunkIndex)) {
                bytesReceived += header.chunkLength
                if (inactivityPaused) {
                    transition(TransferEvent.LinkRestored)
                    inactivityPaused = false
                }
                onProgress()
                newSinceAck++
                if (newSinceAck >= ChunkPlan.ACK_EVERY_N_CHUNKS) sendAck()
                checkpoint()
            }
        }
    }

    suspend fun onEnd(body: TransferEndBody): ReceiveEndStatus =
        storageMutex.withLock {
            if (state.phase == TransferPhase.Verifying) return@withLock ReceiveEndStatus.READY_TO_VERIFY
            if (state.phase != TransferPhase.Transferring) {
                throw TransferProtocolException("TRANSFER_END in phase ${state.phase::class.simpleName}")
            }
            sendAck()
            checkpoint(force = true)
            try {
                withContext(ioDispatcher) { closeFile() }
            } catch (e: IOException) {
                throw TransferStorageException("Close failed for $partFile", e)
            }
            if (ranges.isComplete(plan.chunkCount)) {
                state.on(TransferEvent.TransferEnded)
                ReceiveEndStatus.READY_TO_VERIFY
            } else {
                state.on(TransferEvent.FatalError(TransferError(TransferErrorCode.INTERNAL_ERROR)))
                withContext(ioDispatcher) { sidecar.delete() }
                ReceiveEndStatus.INCOMPLETE
            }
        }

    suspend fun cancel(error: TransferError) {
        storageMutex.withLock {
            if (state.isTerminal || !transition(TransferEvent.CancelRequested(error))) return
            discard()
            runCatching {
                wire.sendCancel(
                    TransferCancelBody(metadata.transferId, error.code, error.detail.ifBlank { null }),
                )
            }
        }
    }

    suspend fun terminate(error: TransferError) {
        storageMutex.withLock {
            if (state.isTerminal || !transition(TransferEvent.FatalError(error))) return
            discard()
        }
    }

    suspend fun onPeerCancel(body: TransferCancelBody) {
        if (body.transferId !=
            metadata.transferId
        ) {
            throw TransferProtocolException("CANCEL for another transfer: ${body.transferId}")
        }
        cancel(TransferError(body.code, body.detail ?: ""))
    }

    suspend fun onPeerError(body: TransferErrorBody) {
        if (body.transferId !=
            metadata.transferId
        ) {
            throw TransferProtocolException("ERROR for another transfer: ${body.transferId}")
        }
        terminate(TransferError(body.code, body.detail ?: ""))
    }

    suspend fun onInactivityExpired() {
        storageMutex.withLock {
            if (state.phase is TransferPhase.Transferring) {
                inactivityPaused = transition(TransferEvent.LinkLost)
                checkpoint(force = true)
            }
        }
    }

    suspend fun onLinkLost() {
        storageMutex.withLock {
            inactivityPaused = false
            if (state.phase is TransferPhase.Transferring) transition(TransferEvent.LinkLost)
            if (!state.isTerminal) checkpoint(force = true)
        }
    }

    suspend fun onLinkRestored() {
        storageMutex.withLock {
            if (state.phase is TransferPhase.Paused) transition(TransferEvent.LinkRestored)
        }
    }

    suspend fun onReconnectWindowExpired() {
        terminate(TransferError(TransferErrorCode.CONNECTION_LOST, "Link lost and the reconnect window elapsed"))
    }

    suspend fun onAcceptToStartExpired() {
        storageMutex.withLock {
            if (state.phase !is TransferPhase.Accepted) return
            fail(TransferErrorCode.TRANSFER_TIMEOUT, "No TRANSFER_START within the accept-to-start window")
        }
    }

    suspend fun onLocalFailure(
        code: TransferErrorCode,
        detail: String,
    ) {
        storageMutex.withLock { fail(code, detail) }
    }

    private suspend fun fail(
        code: TransferErrorCode,
        detail: String,
    ) {
        if (state.isTerminal || !transition(TransferEvent.FatalError(TransferError(code, detail)))) return
        discard()
        runCatching { wire.sendError(TransferErrorBody(metadata.transferId, code, detail)) }
    }

    /** Releases the temp file, preserving the latest resumable progress on controlled shutdown. */
    suspend fun abandon() {
        withContext(NonCancellable) {
            storageMutex.withLock {
                try {
                    if (file != null) checkpoint(force = true)
                } finally {
                    withContext(ioDispatcher) { runCatching { closeFile() } }
                }
            }
        }
    }

    private suspend fun discard() {
        withContext(ioDispatcher + NonCancellable) {
            try {
                runCatching { closeFile() }
            } finally {
                partFile.delete()
                sidecar.delete()
            }
        }
    }

    private suspend fun checkpoint(force: Boolean = false) {
        val now = nowNanos()
        if (!force &&
            (
                bytesReceived == checkpointBytes ||
                    (bytesReceived - checkpointBytes < CHECKPOINT_BYTES && now - checkpointNanos < CHECKPOINT_NANOS)
            )
        ) {
            return
        }
        val snapshot = ranges.coalescedRanges()
        withContext(ioDispatcher) { sidecar.flush(snapshot) }
        checkpointBytes = bytesReceived
        checkpointNanos = now
    }

    private suspend fun sendAck() {
        wire.sendAck(
            ChunkAckBody(
                transferId = metadata.transferId,
                received = ranges.coalescedRanges().map { IndexRange.of(it) },
                highestContiguous = ranges.highestContiguous,
            ),
        )
        newSinceAck = 0
    }

    private fun transition(event: TransferEvent): Boolean =
        try {
            state.on(event)
            true
        } catch (e: IllegalTransferTransition) {
            false
        }

    private fun closeFile() {
        val opened = file ?: return
        file = null
        try {
            opened.fd.sync()
        } finally {
            opened.close()
        }
    }

    private companion object {
        const val CHECKPOINT_BYTES = 16L * 1024 * 1024
        const val CHECKPOINT_NANOS = 1_000_000_000L
    }
}
