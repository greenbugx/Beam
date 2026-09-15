package com.beam.app.protocol.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferStateTest {
    private fun machineOf(phase: TransferPhase) = TransferStateMachine(phase)

    @Test
    fun `happy path walks offered to completed`() {
        val machine = machineOf(TransferPhase.Offered)
        assertEquals(TransferPhase.Accepted, machine.on(TransferEvent.FileAccepted))
        assertEquals(TransferPhase.Transferring, machine.on(TransferEvent.TransferStarted))
        assertEquals(TransferPhase.Verifying, machine.on(TransferEvent.TransferEnded))
        assertEquals(TransferPhase.Completed, machine.on(TransferEvent.HashMatched))
        assertTrue(machine.isTerminal)
    }

    @Test
    fun `rejected expired and cancelled offers are terminal`() {
        listOf(
            TransferEvent.FileRejected to TransferPhase.Rejected,
            TransferEvent.OfferExpired to TransferPhase.Expired,
            TransferEvent.CancelRequested(TransferError(TransferErrorCode.TRANSFER_CANCELLED)) to
                TransferPhase.Cancelled,
        ).forEach { (event, expected) ->
            val machine = machineOf(TransferPhase.Offered)
            assertEquals(expected, machine.on(event))
            assertTrue(machine.isTerminal)
        }
    }

    @Test
    fun `link loss pauses and restore resumes`() {
        val machine = machineOf(TransferPhase.Transferring)
        assertEquals(TransferPhase.Paused, machine.on(TransferEvent.LinkLost))
        assertFalse(machine.isTerminal)
        assertEquals(TransferPhase.Transferring, machine.on(TransferEvent.LinkRestored))
    }

    @Test
    fun `cancel works from offered transferring and paused`() {
        listOf(TransferPhase.Offered, TransferPhase.Transferring, TransferPhase.Paused).forEach { phase ->
            val machine = machineOf(phase)
            assertEquals(
                TransferPhase.Cancelled,
                machine.on(TransferEvent.CancelRequested(TransferError(TransferErrorCode.TRANSFER_CANCELLED))),
            )
        }
    }

    @Test
    fun `fatal errors fail from transferring paused and verifying`() {
        listOf(TransferPhase.Transferring, TransferPhase.Paused, TransferPhase.Verifying).forEach { phase ->
            val machine = machineOf(phase)
            assertEquals(
                TransferPhase.Failed,
                machine.on(TransferEvent.FatalError(TransferError(TransferErrorCode.INTERNAL_ERROR))),
            )
        }
    }

    @Test
    fun `hash mismatch fails verification`() {
        val machine = machineOf(TransferPhase.Verifying)
        assertEquals(TransferPhase.Failed, machine.on(TransferEvent.HashMismatched))
    }

    @Test
    fun `no state may skip verifying`() {
        val machine = machineOf(TransferPhase.Accepted)
        machine.on(TransferEvent.TransferStarted)
        try {
            machine.on(TransferEvent.HashMatched)
            throw AssertionError("expected IllegalTransferTransition")
        } catch (expected: IllegalTransferTransition) {
            assertEquals(TransferPhase.Transferring, machine.phase)
        }
    }

    @Test
    fun `terminal states reject everything`() {
        listOf(
            TransferPhase.Completed to TransferEvent.FileAccepted,
            TransferPhase.Rejected to TransferEvent.TransferStarted,
            TransferPhase.Failed to TransferEvent.LinkRestored,
            TransferPhase.Cancelled to
                TransferEvent.CancelRequested(TransferError(TransferErrorCode.TRANSFER_CANCELLED)),
            TransferPhase.Expired to TransferEvent.FileAccepted,
        ).forEach { (phase, event) ->
            val machine = machineOf(phase)
            try {
                machine.on(event)
                throw AssertionError("expected IllegalTransferTransition from $phase")
            } catch (expected: IllegalTransferTransition) {
            }
        }
    }

    @Test
    fun `dead offer cannot resurrect and retry needs a new transfer`() {
        val machine = machineOf(TransferPhase.Rejected)
        try {
            machine.on(TransferEvent.FileAccepted)
            throw AssertionError("expected IllegalTransferTransition")
        } catch (expected: IllegalTransferTransition) {
        }
    }
}
