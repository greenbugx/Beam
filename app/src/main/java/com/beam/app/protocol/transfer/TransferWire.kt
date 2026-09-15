package com.beam.app.protocol.transfer

import com.beam.app.protocol.ChunkHeader

interface TransferWire {
    suspend fun sendStart(body: TransferStartBody)

    /**
     * Sends one DATA frame.
     *
     * The sender reuses a single chunk buffer
     * so implementations MUST copy or hand off [payload] before returning
     * retaining the array past this call would let the
     * next chunk overwrite it.
     */
    suspend fun sendChunk(
        header: ChunkHeader,
        payload: ByteArray,
    )

    suspend fun sendAck(body: ChunkAckBody)

    suspend fun sendEnd(body: TransferEndBody)

    /** Receiver → sender after a hash match. */
    suspend fun sendVerified(body: TransferVerifiedBody)

    /** Receiver → sender on hash mismatch. */
    suspend fun sendVerifyFailed(body: VerifyFailedBody)
}

class TransferStorageException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
