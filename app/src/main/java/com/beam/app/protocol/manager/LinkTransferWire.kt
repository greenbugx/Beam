package com.beam.app.protocol.manager

import com.beam.app.protocol.ChunkHeader
import com.beam.app.protocol.session.LinkSession
import com.beam.app.protocol.transfer.ChunkAckBody
import com.beam.app.protocol.transfer.FileAcceptBody
import com.beam.app.protocol.transfer.FileMetadata
import com.beam.app.protocol.transfer.FileRejectBody
import com.beam.app.protocol.transfer.RejectReason
import com.beam.app.protocol.transfer.TransferCancelBody
import com.beam.app.protocol.transfer.TransferEndBody
import com.beam.app.protocol.transfer.TransferErrorBody
import com.beam.app.protocol.transfer.TransferMessageTypes
import com.beam.app.protocol.transfer.TransferStartBody
import com.beam.app.protocol.transfer.TransferVerifiedBody
import com.beam.app.protocol.transfer.TransferWire
import com.beam.app.protocol.transfer.VerifyFailedBody
import com.beam.app.protocol.transfer.buildTransferEnvelope
import com.beam.app.protocol.transfer.toJsonElement
import kotlinx.serialization.json.JsonElement

internal class LinkTransferWire(
    private val session: LinkSession,
    val transferId: String,
) : TransferWire {
    override suspend fun sendStart(body: TransferStartBody) = ctrl(TransferMessageTypes.START, body.toJsonElement())

    override suspend fun sendChunk(
        header: ChunkHeader,
        payload: ByteArray,
    ) = session.sendData(header, payload)

    override suspend fun sendAck(body: ChunkAckBody) = ctrl(TransferMessageTypes.ACK, body.toJsonElement())

    override suspend fun sendEnd(body: TransferEndBody) = ctrl(TransferMessageTypes.END, body.toJsonElement())

    override suspend fun sendVerified(body: TransferVerifiedBody) =
        ctrl(TransferMessageTypes.VERIFIED, body.toJsonElement())

    override suspend fun sendVerifyFailed(body: VerifyFailedBody) =
        ctrl(TransferMessageTypes.VERIFY_FAILED, body.toJsonElement())

    override suspend fun sendError(body: TransferErrorBody) = ctrl(TransferMessageTypes.ERROR, body.toJsonElement())

    override suspend fun sendCancel(body: TransferCancelBody) = ctrl(TransferMessageTypes.CANCEL, body.toJsonElement())

    suspend fun sendOffer(metadata: FileMetadata) = ctrl(TransferMessageTypes.OFFER, metadata.toJsonElement())

    suspend fun sendAccept() = ctrl(TransferMessageTypes.ACCEPT, FileAcceptBody(transferId).toJsonElement())

    suspend fun sendReject(reason: RejectReason) =
        ctrl(TransferMessageTypes.REJECT, FileRejectBody(transferId, reason).toJsonElement())

    private suspend fun ctrl(
        type: String,
        body: JsonElement?,
    ) {
        val identity = session.localIdentity
        session.sendControl(
            buildTransferEnvelope(
                type = type,
                transferId = transferId,
                sessionId = identity.sessionId,
                deviceId = identity.deviceId,
                messageId = session.nextOutboundMid(),
                body = body,
            ),
        )
    }
}
