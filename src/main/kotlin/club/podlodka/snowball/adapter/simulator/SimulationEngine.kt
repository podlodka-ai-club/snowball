package club.podlodka.snowball.adapter.simulator

import club.podlodka.snowball.config.SimulatorV1Config
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.PromotionOutcome
import club.podlodka.snowball.domain.PromotionScenario
import club.podlodka.snowball.port.SimulationPort
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The hidden market of simulator v1: one scenario and one action in, units sold and gross profit
 * out, and nothing else.
 *
 * A pure function of its inputs and the frozen coefficient tables - no clock, no randomness, no
 * state. That is what lets the Evaluator replay all four actions and trust the comparison, and
 * what makes a rerun after a restart reproduce the same ground truth.
 *
 * Context demand and promotion affinity are applied separately on purpose. If the weekend, the
 * weather and the local event only scaled demand, they would cancel out when actions are compared
 * and memory would have no contextual behaviour to learn.
 */
class SimulationEngine : SimulationPort {
    override fun simulate(
        scenarioId: String,
        scenario: PromotionScenario,
        discount: Discount,
    ): PromotionOutcome {
        require(scenario.category in SimulatorV1Config.SUPPORTED_CATEGORIES) {
            "category '${scenario.category}' has no simulator v1 coefficients"
        }

        val day = SimulatorV1Config.dayFactors(scenario.category, scenario.dayType)
        val weather = SimulatorV1Config.weatherFactors(scenario.category, scenario.weather)
        val event = SimulatorV1Config.eventFactors(scenario.category, scenario.eventType)

        val contextDemand = scenario.baselineSales * day.demand * weather.demand * event.demand
        val promotionAffinity = day.promo * weather.promo * event.promo
        val discountEffect = 1 + SimulatorV1Config.baseDiscountLift(scenario.category, discount) * promotionAffinity

        val noise = DeterministicNoise.forScenario(scenarioId, SimulatorV1Config.VERSION)
        val rawDemand = BigDecimal(contextDemand * discountEffect).multiply(noise)

        // Units round before the stock cap: the cap is a physical limit on what was on the shelf,
        // not another term in the arithmetic.
        val demandUnits = rawDemand.setScale(0, RoundingMode.HALF_UP).toInt()
        val unitsSold = minOf(scenario.stock, maxOf(0, demandUnits))

        // Gross profit uses the already-rounded shelf price, as the guide requires: the customer
        // pays a real price, not an intermediate one. Negative profit is allowed - a deep discount
        // below cost is a lesson worth learning, not an error.
        val discountedPrice =
            scenario.price
                .multiply(BigDecimal(100 - discount.percent))
                .divide(BigDecimal(100))
                .setScale(2, RoundingMode.HALF_UP)
        val grossProfit =
            discountedPrice
                .subtract(scenario.cost)
                .multiply(BigDecimal(unitsSold))
                .setScale(2, RoundingMode.HALF_UP)

        return PromotionOutcome(unitsSold = unitsSold, grossProfit = grossProfit)
    }
}
