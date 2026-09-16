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

val TransferPhase.isTerminal: Boolean
    get() =
        when (this) {
            is TransferPhase.Completed,
            is TransferPhase.Rejected,
            is TransferPhase.Expired,
            is TransferPhase.Cancelled,
            is TransferPhase.Failed,
            -> true

            else -> false
        }

/** Stable error vocabulary carried by TRANSFER_ERROR / FILE_REJECT / SESSION_CLOSE. */
enum class TransferErrorCode {
    INVALID_MESSAGE,
    INVALID_METADATA,
    UNSUPPORTED_VERSION,
    UNSUPPORTED_FEATURE,
    TRANSFER_REJECTED,
    TRANSFER_CANCELLED,
    INSUFFICIENT_STORAGE,
    CONNECTION_LOST,
    CHUNK_INVALID,
    HASH_MISMATCH,
    TRANSFER_TIMEOUT,
    PERMISSION_DENIED,
    RESOURCE_EXHAUSTED,
    INTERNAL_ERROR,
    AUTH_FAILED,
}

data class TimeoutPolicy(
    val handshakeMillis: Long = 10_000,
    val offerMillis: Long = 60_000,
    val acceptToStartMillis: Long = 10_000,
    val transferInactivityMillis: Long = 30_000,
    val maxConsecutiveInactivity: Int = 3,
    val ackMillis: Long = 15_000,
    val verificationMillis: Long = 120_000,
    val reconnectWindowMillis: Long = 120_000,
) {
    init {
        require(handshakeMillis > 0 && offerMillis > 0 && acceptToStartMillis > 0) {
            "Timeouts must be positive"
        }
        require(transferInactivityMillis > 0 && ackMillis > 0 && verificationMillis > 0) {
            "Timeouts must be positive"
        }
        require(reconnectWindowMillis > 0) { "Reconnect window must be positive" }
        require(maxConsecutiveInactivity >= 1) { "maxConsecutiveInactivity must be >= 1" }
    }

    companion object {
        /** Section 31 recommended starting values. */
        val RECOMMENDED: TimeoutPolicy = TimeoutPolicy()
    }
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

    /** Cancelled by [TransferError], either side. */
    data class CancelRequested(
        val error: TransferError,
    ) : TransferEvent

    /** Any fatal condition; [TransferError] names the Section 30 vocabulary entry. */
    data class FatalError(
        val error: TransferError,
    ) : TransferEvent
}

/** One protocol error: Section 30 enum + short human-readable detail (never stack traces). */
data class TransferError(
    val code: TransferErrorCode,
    val detail: String = "",
)

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
        get() = phase.isTerminal

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
                        is TransferEvent.FatalError -> TransferPhase.Failed
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
