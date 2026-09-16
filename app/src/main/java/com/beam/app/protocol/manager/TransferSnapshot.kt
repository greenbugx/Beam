package com.beam.app.protocol.manager

import com.beam.app.protocol.transfer.TransferError
import com.beam.app.protocol.transfer.TransferPhase
import com.beam.app.protocol.transfer.isTerminal

enum class TransferDirection {
    SENDING,
    RECEIVING,
}

data class TransferSnapshot(
    val transferId: String,
    val linkId: String,
    val peerName: String,
    val fileName: String,
    val sizeBytes: Long,
    val direction: TransferDirection,
    val phase: TransferPhase,
    val bytesTransferred: Long,
    val bytesPerSecond: Long,
    val etaSeconds: Long?,
    val error: TransferError? = null,
) {
    val isTerminal: Boolean get() = phase.isTerminal

    val progressFraction: Float
        get() {
            if (sizeBytes > 0L) {
                return (bytesTransferred.toDouble() / sizeBytes).coerceIn(0.0, 1.0).toFloat()
            }
            val done = phase.isTerminal && phase !is TransferPhase.Failed && phase !is TransferPhase.Cancelled
            return if (done) 1f else 0f
        }

    val remainingBytes: Long get() = (sizeBytes - bytesTransferred).coerceAtLeast(0L)
}
