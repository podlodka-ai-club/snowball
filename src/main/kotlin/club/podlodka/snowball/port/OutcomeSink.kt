package club.podlodka.snowball.port

import club.podlodka.snowball.domain.PromotionOutcomeEvent

/**
 * Accepts a completed outcome from the Market Simulator.
 *
 * The interface names no transport on purpose: whether the outcome travels in process, over a
 * broker, or over HTTP is a later decision that this boundary does not need to anticipate.
 */
fun interface OutcomeSink {
    fun accept(outcome: PromotionOutcomeEvent)
}
