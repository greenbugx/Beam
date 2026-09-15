package com.beam.app.protocol.transfer

import java.io.File

enum class CompletionStatus {
    /** Hash matched; file published at the destination. */
    PUBLISHED,

    /** Hash mismatched; temp deleted, sender notified. */
    VERIFY_FAILED,
}

class TransferCompleter(
    private val metadata: FileMetadata,
    private val tempDir: File,
    private val wire: TransferWire,
    private val sidecar: RangesSidecar = RangesSidecar(tempDir, metadata.transferId),
    private val timeouts: TransferTimeouts = TransferTimeouts(),
) {
    var destinationUsed: File? = null
        private set

    val partFile: File
        get() = File(tempDir, "${metadata.transferId}.part")

    /**
     * Verifies and publishes.
     *
     * @param destination final file location; its parent must exist.
     * @return [CompletionStatus.PUBLISHED] on hash match (file now at
     *   [destination], temp gone, `TRANSFER_VERIFIED` sent), or
     *   [CompletionStatus.VERIFY_FAILED] on mismatch (temp deleted,
     *   `VERIFY_FAILED` sent).
     * @throws TransferStorageException when hashing/publishing hits storage errors (temp is cleaned up first).
     */
    suspend fun complete(destination: File): CompletionStatus {
        if (!partFile.exists()) {
            throw TransferStorageException("Missing temp file for transfer ${metadata.transferId}")
        }
        return try {
            val actual =
                timeouts.verification("Verification timed out for ${metadata.transferId}") {
                    FileVerifier.hash(partFile)
                }
            if (actual == metadata.sha256) {
                publish(destination)
                wire.sendVerified(TransferVerifiedBody(metadata.transferId, metadata.sha256))
                sidecar.delete()
                CompletionStatus.PUBLISHED
            } else {
                deleteTemp()
                sidecar.delete()
                wire.sendVerifyFailed(
                    VerifyFailedBody(metadata.transferId, metadata.sha256, actual),
                )
                CompletionStatus.VERIFY_FAILED
            }
        } catch (e: TransferStorageException) {
            throw e
        } catch (e: Exception) {
            deleteTemp()
            sidecar.delete()
            throw TransferStorageException("Verification failed for ${metadata.transferId}", e)
        }
    }

    fun cleanup() {
        deleteTemp()
        sidecar.delete()
    }

    private fun publish(destination: File) {
        val target = resolveCollision(destination)
        try {
            target.parentFile?.mkdirs()
            val moved = partFile.renameTo(target)
            if (moved) {
                partFile.delete()
            } else {
                // Cross-filesystem fallback: copy-then-delete, hash already verified.
                partFile.inputStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                partFile.delete()
            }
            destinationUsed = target
        } catch (e: java.io.IOException) {
            deleteTemp()
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

    private fun deleteTemp() {
        partFile.delete()
    }
}
