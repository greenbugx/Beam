package com.beam.app.protocol.transfer

/** Transfer phases. */
sealed interface TransferPhase {
    data object Offered : TransferPhase

    data object Accepted : TransferPhase

    data object Transferring : TransferPhase

    data object Paused : TransferPhase

    data object Verifying : TransferPhase

    data object Completed : TransferPhase

    data object Rejected : TransferPhase

    data object Expired : TransferPhase

    data object Cancelled : TransferPhase

    data object Failed : TransferPhase
}

/** Drivers.
 * Check Section 18 of PROTOCOL.md for the state machine table.
 */
sealed interface TransferEvent {
    data object FileAccepted : TransferEvent

    data object FileRejected : TransferEvent

    data object OfferExpired : TransferEvent

    data object TransferStarted : TransferEvent

    /** All chunks present; TRANSFER_END processed. */
    data object TransferEnded : TransferEvent

    data object LinkLost : TransferEvent

    data object LinkRestored : TransferEvent

    data object HashMatched : TransferEvent

    data object HashMismatched : TransferEvent

    data object CancelRequested : TransferEvent

    data object FatalError : TransferEvent
}

class IllegalTransferTransition(
    from: TransferPhase,
    event: TransferEvent,
) : Exception("Illegal transition: ${phaseName(from)} cannot handle ${event::class.simpleName}")

private fun phaseName(phase: TransferPhase) = phase::class.simpleName ?: "Unknown"

class TransferStateMachine(
    initial: TransferPhase,
) {
    var phase: TransferPhase = initial
        private set

    val isTerminal: Boolean
        get() =
            when (phase) {
                is TransferPhase.Completed,
                is TransferPhase.Rejected,
                is TransferPhase.Expired,
                is TransferPhase.Cancelled,
                is TransferPhase.Failed,
                -> true

                else -> false
            }

    /**
     * Applies [event] and returns the new phase.
     *
     * @throws IllegalTransferTransition when the table has no such edge.
     */
    fun on(event: TransferEvent): TransferPhase {
        val next =
            when (phase) {
                is TransferPhase.Offered -> {
                    when (event) {
                        is TransferEvent.FileAccepted -> TransferPhase.Accepted
                        is TransferEvent.FileRejected -> TransferPhase.Rejected
                        is TransferEvent.OfferExpired -> TransferPhase.Expired
                        is TransferEvent.CancelRequested -> TransferPhase.Cancelled
                        else -> null
                    }
                }

                is TransferPhase.Accepted -> {
                    when (event) {
                        is TransferEvent.TransferStarted -> TransferPhase.Transferring
                        else -> null
                    }
                }

                is TransferPhase.Transferring -> {
                    when (event) {
                        is TransferEvent.TransferEnded -> TransferPhase.Verifying
                        is TransferEvent.LinkLost -> TransferPhase.Paused
                        is TransferEvent.CancelRequested -> TransferPhase.Cancelled
                        is TransferEvent.FatalError -> TransferPhase.Failed
                        else -> null
                    }
                }

                is TransferPhase.Paused -> {
                    when (event) {
                        is TransferEvent.LinkRestored -> TransferPhase.Transferring
                        is TransferEvent.CancelRequested -> TransferPhase.Cancelled
                        is TransferEvent.FatalError -> TransferPhase.Failed
                        else -> null
                    }
                }

                is TransferPhase.Verifying -> {
                    when (event) {
                        is TransferEvent.HashMatched -> TransferPhase.Completed
                        is TransferEvent.HashMismatched -> TransferPhase.Failed
                        is TransferEvent.FatalError -> TransferPhase.Failed
                        else -> null
                    }
                }

                else -> {
                    null
                }
            }
        if (next == null) throw IllegalTransferTransition(phase, event)
        phase = next
        return next
    }
}
