package club.podlodka.snowball.port

import club.podlodka.snowball.domain.PromotionDecisionEvent

/**
 * Accepts a decision from the Promotion Agent and returns nothing.
 *
 * Narrow on purpose. The agent must not hold `SimulationPort`: anything with a real simulation can
 * call it once per allowed discount and reconstruct the counterfactual profits for the scenario -
 * exactly the ground truth the agent is supposed to learn rather than read. Handing off through a
 * sink that answers nothing keeps that door shut.
 */
fun interface DecisionSink {
    fun accept(decision: PromotionDecisionEvent)
}
