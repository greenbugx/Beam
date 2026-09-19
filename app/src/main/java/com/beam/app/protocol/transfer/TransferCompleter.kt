package com.beam.app.protocol.transfer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterInputStream
import kotlin.coroutines.CoroutineContext

enum class CompletionStatus {
    /** Hash matched; file published at the destination. */
    PUBLISHED,

    /** Hash mismatched; temp deleted, sender notified. */
    VERIFY_FAILED,
}

class TransferCompleter(
    val metadata: FileMetadata,
    private val tempDir: File,
    private val wire: TransferWire,
    private val sidecar: RangesSidecar = RangesSidecar(tempDir, metadata.transferId),
    private val timeouts: TransferTimeouts = TransferTimeouts(),
    private val state: TransferStateMachine? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val storageMutex: Mutex = Mutex(),
) {
    var destinationUsed: File? = null
        private set

    /** Digest computed by the last [complete] call, for mismatch diagnostics. */
    var lastActualHash: String? = null
        private set

    val partFile: File
        get() = File(tempDir, "${metadata.transferId}.part")

    suspend fun complete(destination: File): CompletionStatus =
        storageMutex.withLock {
            currentCoroutineContext().ensureActive()
            if (state != null && state.phase != TransferPhase.Verifying) {
                throw TransferCancelledException(TransferError(TransferErrorCode.TRANSFER_CANCELLED))
            }
            var published: File? = null
            var committed = false
            try {
                val actual =
                    timeouts.verification("Verification timed out for ${metadata.transferId}") {
                        withContext(ioDispatcher) {
                            val context = currentCoroutineContext()
                            if (!partFile.exists()) {
                                throw TransferStorageException("Missing temp file for transfer ${metadata.transferId}")
                            }
                            partFile.inputStream().use { input ->
                                FileVerifier.hash(
                                    object : FilterInputStream(input) {
                                        override fun read(
                                            buffer: ByteArray,
                                            offset: Int,
                                            length: Int,
                                        ): Int {
                                            context.ensureActive()
                                            return input.read(buffer, offset, length)
                                        }
                                    },
                                )
                            }
                        }
                    }
                lastActualHash = actual
                if (actual == metadata.sha256) {
                    withContext(ioDispatcher) {
                        published = publish(destination, currentCoroutineContext())
                        sidecar.delete()
                    }
                    // If cancellation wins the IO return dispatch, catch below removes the uncommitted publication.
                    currentCoroutineContext().ensureActive()
                    destinationUsed = checkNotNull(published)
                    state?.on(TransferEvent.HashMatched)
                    committed = true
                    wire.sendVerified(TransferVerifiedBody(metadata.transferId, metadata.sha256))
                    CompletionStatus.PUBLISHED
                } else {
                    deleteArtifacts()
                    state?.on(TransferEvent.HashMismatched)
                    wire.sendVerifyFailed(VerifyFailedBody(metadata.transferId, metadata.sha256, actual))
                    CompletionStatus.VERIFY_FAILED
                }
            } catch (e: Exception) {
                withContext(ioDispatcher + NonCancellable) {
                    if (!committed) published?.delete()
                    partFile.delete()
                    sidecar.delete()
                }
                if (e is CancellationException || e is TransferStorageException ||
                    e is TransferCancelledException || e is TransferTimeoutException
                ) {
                    throw e
                }
                throw TransferStorageException("Verification failed for ${metadata.transferId}", e)
            }
        }

    suspend fun cleanup() {
        withContext(NonCancellable) {
            storageMutex.withLock {
                deleteArtifacts()
            }
        }
    }

    private suspend fun deleteArtifacts() {
        withContext(ioDispatcher + NonCancellable) {
            partFile.delete()
            sidecar.delete()
        }
    }

    private fun publish(
        destination: File,
        context: CoroutineContext,
    ): File {
        context.ensureActive()
        val target = resolveCollision(destination)
        try {
            target.parentFile?.mkdirs()
            context.ensureActive()
            if (!partFile.renameTo(target)) {
                // Cross-filesystem fallback: bounded copy, fsynced before exposing completion.
                partFile.inputStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            context.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
                context.ensureActive()
                partFile.delete()
            }
            return target
        } catch (e: Exception) {
            target.delete()
            if (e is CancellationException) throw e
            throw TransferStorageException("Cannot publish to $target", e)
        }
    }

    private fun resolveCollision(destination: File): File {
        if (!destination.exists()) return destination
        val name = destination.nameWithoutExtension
        val ext = destination.extension
        var candidate = destination
        var n = 0
        while (candidate.exists()) {
            n++
            val suffixed = if (ext.isEmpty()) "$name ($n)" else "$name ($n).$ext"
            candidate = File(destination.parentFile, suffixed)
        }
        return candidate
    }
}
