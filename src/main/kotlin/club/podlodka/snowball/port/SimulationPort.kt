package club.podlodka.snowball.port

import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.PromotionOutcome
import club.podlodka.snowball.domain.PromotionScenario

/**
 * Maps one scenario and one allowed discount to the business outcome of that action.
 *
 * `scenarioId` is a separate parameter because it is part of the simulation input, not of the
 * scenario payload: `docs/market-simulator/README.md` derives the deterministic noise factor from
 * `v1|<scenario_id>`, and the committed `scenario` object does not carry the id. An implementation
 * given only the payload could not reproduce the documented shock.
 *
 * The return type is the committed `outcome` payload and nothing more. Coefficients, the
 * deterministic noise factor, counterfactual results, and the oracle-best action are simulator
 * internals and MUST NOT become reachable through this port.
 *
 * The narrow return type is not by itself sufficient to keep ground truth hidden: anything holding
 * a real implementation can call it once per allowed discount and reconstruct the counterfactual
 * profits for the current scenario. This port therefore MUST NOT be wired into the Promotion Agent
 * at all. Replaying all four actions is the Evaluator's job.
 */
fun interface SimulationPort {
    fun simulate(
        scenarioId: String,
        scenario: PromotionScenario,
        discount: Discount,
    ): PromotionOutcome
}
