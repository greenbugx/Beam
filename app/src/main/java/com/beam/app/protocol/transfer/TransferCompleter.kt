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
        try {
            destination.parentFile?.mkdirs()
            val moved = partFile.renameTo(destination)
            if (!moved) {
                // Cross-filesystem fallback: copy-then-delete, hash already verified.
                partFile.inputStream().use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                deleteTemp()
            }
        } catch (e: java.io.IOException) {
            deleteTemp()
            throw TransferStorageException("Cannot publish to $destination", e)
        }
    }

    private fun deleteTemp() {
        partFile.delete()
    }
}
