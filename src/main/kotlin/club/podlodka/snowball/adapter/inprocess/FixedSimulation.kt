package club.podlodka.snowball.adapter.inprocess

import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.PromotionOutcome
import club.podlodka.snowball.domain.PromotionScenario
import club.podlodka.snowball.port.SimulationPort

/**
 * A [SimulationPort] that returns the outcome it was given and calculates nothing.
 *
 * It exists so that code depending on the port can be exercised before the Market Simulator is
 * implemented. Because it holds no coefficients and derives nothing from the scenario, it cannot
 * become an accidental second source of simulator ground truth.
 */
class FixedSimulation(
    private val outcome: PromotionOutcome,
) : SimulationPort {
    private val invocations = mutableListOf<Triple<String, PromotionScenario, Discount>>()

    /** Scenario id, scenario, and discount this port was called with, in call order. */
    val calls: List<Triple<String, PromotionScenario, Discount>> get() = invocations.toList()

    override fun simulate(
        scenarioId: String,
        scenario: PromotionScenario,
        discount: Discount,
    ): PromotionOutcome {
        invocations += Triple(scenarioId, scenario, discount)
        return outcome
    }
}
