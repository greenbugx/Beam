package com.beam.app.protocol.transfer

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

/** Raised when a Section 31 timer expires; maps to `TRANSFER_TIMEOUT` (Section 30). */
class TransferTimeoutException(
    detail: String,
) : Exception(detail)

/**
 * Timeout policy enforcer.
 */
class TransferTimeouts(
    private val policy: TimeoutPolicy = TimeoutPolicy.RECOMMENDED,
) {
    /** Runs [block] under the [millis] budget; expiry raises [TransferTimeoutException]. */
    suspend fun <T> bounded(
        millis: Long,
        detail: String,
        block: suspend () -> T,
    ): T =
        try {
            withTimeout(millis.milliseconds) { block() }
        } catch (e: TimeoutCancellationException) {
            throw TransferTimeoutException(detail)
        }

    /** Sender inactivity timer while waiting for ACK progress. */
    suspend fun <T> inactivity(
        detail: String,
        block: suspend () -> T,
    ): T = bounded(policy.transferInactivityMillis, detail, block)

    /** Offer/decision timer. */
    suspend fun <T> offer(
        detail: String,
        block: suspend () -> T,
    ): T = bounded(policy.offerMillis, detail, block)

    /** Accept→Start gap timer. */
    suspend fun <T> acceptToStart(
        detail: String,
        block: suspend () -> T,
    ): T = bounded(policy.acceptToStartMillis, detail, block)

    /** Verification timer. */
    suspend fun <T> verification(
        detail: String,
        block: suspend () -> T,
    ): T = bounded(policy.verificationMillis, detail, block)
}
