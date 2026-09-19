package com.beam.app.session

import com.beam.app.protocol.manager.TransferDirection
import com.beam.app.protocol.manager.TransferSnapshot
import com.beam.app.protocol.transfer.TransferPhase

internal fun TransferSnapshot.toBeamTransfer(): BeamTransfer =
    BeamTransfer(
        id = transferId,
        fileName = fileName,
        direction =
            when (direction) {
                TransferDirection.SENDING -> BeamTransferDirection.Sending
                TransferDirection.RECEIVING -> BeamTransferDirection.Receiving
            },
        status =
            when (phase) {
                is TransferPhase.Offered,
                is TransferPhase.Accepted,
                is TransferPhase.Transferring,
                is TransferPhase.Verifying,
                -> BeamTransferStatus.Active

                is TransferPhase.Paused -> BeamTransferStatus.Paused

                is TransferPhase.Completed -> BeamTransferStatus.Completed

                is TransferPhase.Rejected,
                is TransferPhase.Expired,
                is TransferPhase.Cancelled,
                is TransferPhase.Failed,
                -> BeamTransferStatus.Failed
            },
        totalBytes = sizeBytes,
        progressFraction =
            if (sizeBytes > 0L) {
                (bytesTransferred.toFloat() / sizeBytes).coerceIn(0f, 1f)
            } else {
                1f
            },
        speedBytesPerSecond = bytesPerSecond.coerceAtLeast(0L),
        peerLabel = peerName,
    )

/** Maps an offered snapshot onto the accept/reject prompt model. */
internal fun TransferSnapshot.toIncomingOffer(): BeamIncomingOffer =
    BeamIncomingOffer(
        transferId = transferId,
        fileName = fileName,
        sizeBytes = sizeBytes,
        peerLabel = peerName,
    )
