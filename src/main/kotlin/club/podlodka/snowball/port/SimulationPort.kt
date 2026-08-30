package club.podlodka.snowball.port

import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.PromotionOutcome
import club.podlodka.snowball.domain.PromotionScenario

/**
 * Maps one scenario and one allowed discount to the business outcome of that action.
 *
 * The return type is the committed `outcome` payload and nothing more. Coefficients, the
 * deterministic noise factor, counterfactual results, and the oracle-best action are simulator
 * internals and MUST NOT become reachable through this port; the Promotion Agent would otherwise
 * be able to read the ground truth it is supposed to be learning.
 */
fun interface SimulationPort {
    fun simulate(
        scenario: PromotionScenario,
        discount: Discount,
    ): PromotionOutcome
}
