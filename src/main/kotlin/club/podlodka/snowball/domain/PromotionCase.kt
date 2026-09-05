package club.podlodka.snowball.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Immutable evaluated evidence for one finished promotion.
 *
 * Unlike the outcome contract, this carries the counterfactuals: what every allowed action would
 * have earned. That is what makes a case worth keeping - the chosen action alone says nothing
 * about whether it was the right one, and a lesson aggregates the whole comparison rather than the
 * single observed result.
 *
 * It never leaves the learning side. The Promotion Agent reads Lessons, not cases.
 */
data class PromotionCase(
    val caseId: String,
    val scenarioId: String,
    val simulatorVersion: SimulatorVersion,
    val scenario: PromotionScenario,
    val chosenDiscount: Discount,
    val chosenUnitsSold: Int,
    val chosenGrossProfit: BigDecimal,
    val profitByDiscount: Map<Discount, BigDecimal>,
    val bestDiscount: Discount,
) {
    init {
        require(caseId.isNotEmpty()) { "case_id must not be empty" }
        require(scenarioId.isNotEmpty()) { "scenario_id must not be empty" }
        require(profitByDiscount.keys == Discount.entries.toSet()) {
            "a case must carry a profit for every allowed action, had ${profitByDiscount.keys}"
        }
    }

    val bestGrossProfit: BigDecimal get() = profitByDiscount.getValue(bestDiscount)

    /** What the chosen action gave up against the best one. Never negative by construction. */
    val regret: BigDecimal get() = bestGrossProfit.subtract(chosenGrossProfit).setScale(2, RoundingMode.HALF_UP)

    val regretPct: BigDecimal
        get() {
            val denominator = bestGrossProfit.abs().max(BigDecimal("0.01"))
            return regret
                .divide(denominator, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
                .setScale(2, RoundingMode.HALF_UP)
        }

    /** What a lesson aggregates: the four columns and the winner, without the scenario. */
    val evidence: CaseEvidence get() = CaseEvidence(caseId, profitByDiscount, bestDiscount)

    /** The two buckets this case teaches. */
    val lessonKeys: List<LessonKey> get() = LessonKey.bucketsFor(scenario)
}
