package club.podlodka.snowball.ports.inprocess

import club.podlodka.snowball.contracts.Discount
import club.podlodka.snowball.contracts.PromotionOutcome
import club.podlodka.snowball.contracts.PromotionScenario
import club.podlodka.snowball.ports.SimulationPort

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
    private val invocations = mutableListOf<Pair<PromotionScenario, Discount>>()

    /** Scenario/discount pairs this port was called with, in call order. */
    val calls: List<Pair<PromotionScenario, Discount>> get() = invocations.toList()

    override fun simulate(
        scenario: PromotionScenario,
        discount: Discount,
    ): PromotionOutcome {
        invocations += scenario to discount
        return outcome
    }
}
