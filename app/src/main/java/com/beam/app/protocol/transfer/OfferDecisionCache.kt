package com.beam.app.protocol.transfer

import java.util.concurrent.ConcurrentHashMap

class OfferDecisionCache {
    enum class Decision {
        ACCEPTED,
        REJECTED,
    }

    private val decisions = ConcurrentHashMap<String, Decision>()

    fun firstArrival(
        offerId: String,
        decide: () -> Decision,
    ): Decision? = decisions.computeIfAbsent(offerId) { decide() }

    /** Records the decision. */
    fun record(
        offerId: String,
        decision: Decision,
    ) {
        decisions[offerId] = decision
    }

    /** Last-recorded decision for [offerId], if any. */
    fun decisionFor(offerId: String): Decision? = decisions[offerId]
}
