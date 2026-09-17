package com.beam.app.protocol.logging

import com.beam.app.protocol.session.SessionCloseReason
import com.beam.app.protocol.transfer.TransferErrorCode
import com.beam.app.protocol.transfer.TransferPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SESSION_ID = "sess-ring"
private const val DEVICE_ID = "dev-ring"

class ProtocolLogRetentionTest {
    private class RecordingLogger : ProtocolLogger {
        val events = mutableListOf<ProtocolLogEvent>()

        override fun log(event: ProtocolLogEvent) {
            events += event
        }
    }

    @Test
    fun `invalid message events flow through the link context`() {
        val logger = RecordingLogger()
        val context = LinkLogContext(logger, SESSION_ID, DEVICE_ID)

        context.error(
            errorCode = TransferErrorCode.INVALID_MESSAGE,
            direction = LogDirection.IN,
            detail = "malformed frame kind=TRUNCATED test",
        )
        context.error(
            errorCode = TransferErrorCode.INVALID_MESSAGE,
            direction = LogDirection.IN,
            detail = "malformed frame kind=OVERSIZE test",
        )

        assertEquals(2, logger.events.size)
        assertTrue(logger.events.all { it.errorCode == TransferErrorCode.INVALID_MESSAGE })
        assertTrue(logger.events.all { it.detail?.startsWith("malformed") == true })
        assertTrue(logger.events.all { it.sessionId == SESSION_ID })
    }

    @Test
    fun `close event carries the reason as emitted`() {
        val logger = RecordingLogger()
        val context = LinkLogContext(logger, SESSION_ID, DEVICE_ID)

        context.session(closeReason = SessionCloseReason.USER_LEFT, state = "CLOSED")

        val single = logger.events.single()
        assertEquals(SessionCloseReason.USER_LEFT, single.closeReason)
        assertEquals("CLOSED", single.state)
    }

    @Test
    fun `transfer line carries phase and counters`() {
        val logger = RecordingLogger()
        val context = LinkLogContext(logger, SESSION_ID, DEVICE_ID)

        context.transfer(
            transferId = "tid-ring",
            phase = TransferPhase.Completed,
            bytesTransferred = 1024,
            bytesTotal = 1024,
            fileName = "note.txt",
        )

        val single = logger.events.single()
        assertEquals("tid-ring", single.transferId)
        assertEquals(TransferPhase.Completed, single.phase)
        assertEquals(1024L, single.bytesTransferred)
        assertEquals(1024L, single.bytesTotal)
    }

    @Test
    fun `a throwing sink never propagates back into the protocol`() {
        val context = LinkLogContext(ThrowingLogger(), SESSION_ID, DEVICE_ID)

        context.error(errorCode = TransferErrorCode.CONNECTION_LOST, detail = "boom")
        context.session(state = "ACTIVE")
        context.transfer(transferId = "tid-x")
    }

    private class ThrowingLogger : ProtocolLogger {
        override fun log(event: ProtocolLogEvent): Unit = throw IllegalStateException("sink exploded")
    }
}
