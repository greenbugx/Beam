package com.beam.app.protocol.transfer

import java.util.concurrent.ConcurrentHashMap

class OfferDecisionCache {
    enum class Decision {
        ACCEPTED,
        REJECTED,
    }

    private data class Entry(
        val decision: Decision,
        val rejectReason: RejectReason?,
    )

    private val decisions = ConcurrentHashMap<String, Entry>()

    fun firstArrival(
        offerId: String,
        decide: () -> Decision,
    ): Decision? = decisions.computeIfAbsent(offerId) { entry(decide(), RejectReason.USER_REJECTED) }.decision

    /** Records the decision. */
    fun record(
        offerId: String,
        decision: Decision,
        rejectReason: RejectReason = RejectReason.USER_REJECTED,
    ) {
        decisions[offerId] = entry(decision, rejectReason)
    }

    /** Last-recorded decision for [offerId], if any. */
    fun decisionFor(offerId: String): Decision? = decisions[offerId]?.decision

    fun rejectionReasonFor(offerId: String): RejectReason? = decisions[offerId]?.rejectReason

    private fun entry(
        decision: Decision,
        reason: RejectReason,
    ): Entry = Entry(decision, reason.takeIf { decision == Decision.REJECTED })
}
