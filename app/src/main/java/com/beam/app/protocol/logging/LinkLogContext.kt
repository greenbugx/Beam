package com.beam.app.protocol.logging

import com.beam.app.protocol.session.SessionCloseReason
import com.beam.app.protocol.transfer.TransferErrorCode
import com.beam.app.protocol.transfer.TransferPhase

class LinkLogContext(
    private val logger: ProtocolLogger,
    private val sessionId: String,
    private val localDeviceId: String,
) {
    fun session(
        messageType: String? = null,
        direction: LogDirection = LogDirection.LOCAL,
        messageId: String? = null,
        remoteDeviceId: String? = null,
        state: String? = null,
        closeReason: SessionCloseReason? = null,
        detail: String? = null,
    ) {
        emit(
            ProtocolLogEvent(
                category = LogCategory.SESSION,
                direction = direction,
                messageType = messageType,
                messageId = messageId,
                state = state,
                closeReason = closeReason,
                detail = detail,
                deviceId = deviceFor(direction, remoteDeviceId),
            ),
        )
    }

    fun transfer(
        transferId: String,
        phase: TransferPhase? = null,
        direction: LogDirection = LogDirection.LOCAL,
        ranges: List<LongRange> = emptyList(),
        bytesTransferred: Long? = null,
        bytesTotal: Long? = null,
        fileName: String? = null,
        detail: String? = null,
    ) {
        emit(
            ProtocolLogEvent(
                category = LogCategory.TRANSFER,
                direction = direction,
                sessionId = sessionId,
                transferId = transferId,
                phase = phase,
                ranges = ranges,
                bytesTransferred = bytesTransferred,
                bytesTotal = bytesTotal,
                fileName = fileName,
                detail = detail,
            ),
        )
    }

    fun error(
        errorCode: TransferErrorCode,
        transferId: String? = null,
        detail: String? = null,
        expectedHash: String? = null,
        actualHash: String? = null,
        phase: TransferPhase? = null,
        direction: LogDirection = LogDirection.LOCAL,
    ) {
        emit(
            ProtocolLogEvent(
                category = LogCategory.ERROR,
                direction = direction,
                sessionId = sessionId,
                transferId = transferId,
                phase = phase,
                errorCode = errorCode,
                expectedHash = expectedHash,
                actualHash = actualHash,
                detail = detail,
            ),
        )
    }

    private fun deviceFor(
        direction: LogDirection,
        remoteDeviceId: String?,
    ): String = if (direction == LogDirection.IN && remoteDeviceId != null) remoteDeviceId else localDeviceId

    private fun emit(event: ProtocolLogEvent) {
        runCatching { logger.log(event) }
    }
}
