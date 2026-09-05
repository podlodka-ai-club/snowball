package club.podlodka.snowball.application

import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.Lesson
import club.podlodka.snowball.domain.PromotionCase
import club.podlodka.snowball.domain.PromotionOutcomeEvent
import club.podlodka.snowball.port.LearningMemory
import club.podlodka.snowball.port.SimulationPort
import java.math.BigDecimal
import java.util.logging.Logger

/** The evidence one finished promotion produced, and what it taught. */
data class EvaluationResult(
    val case: PromotionCase,
    val lessons: List<Lesson>,
    val learned: Boolean,
) {
    val regret: BigDecimal get() = case.regret
}

/** The chosen action's replay disagreed with the outcome that was handed over. */
class IntegrityError(
    message: String,
) : IllegalStateException(message)

/**
 * Closes the loop: replay every allowed action, find what was best, measure what the chosen action
 * gave up, and turn that into lessons.
 *
 * This is deliberately the only place that holds a real `SimulationPort`. Four calls to it
 * reconstruct the counterfactual profits for a scenario, which is exactly the ground truth the
 * agent is meant to learn rather than read - so the port stays here and in the simulator, and
 * never reaches the agent.
 */
class PromotionEvaluator(
    private val simulation: SimulationPort,
    private val memory: LearningMemory,
    private val learningEnabled: Boolean = true,
) {
    fun evaluate(outcome: PromotionOutcomeEvent): EvaluationResult {
        val caseId = "CASE-${outcome.simulatorVersion.wire}-${outcome.scenarioId}"

        // Replaying everything before writing anything: a partial evaluation is worse than none,
        // because a case with a missing column would poison every lesson aggregating it.
        val profits =
            Discount.entries.associateWith { discount ->
                simulation.simulate(outcome.scenarioId, outcome.scenario, discount).let { it to it.grossProfit }
            }

        // The chosen action must reproduce what the simulator already reported. If it does not,
        // something about the scenario, the version or the arithmetic has drifted, and learning
        // from it would record a fact that never happened.
        val chosenReplay = profits.getValue(outcome.decision.discount).first
        if (chosenReplay.unitsSold != outcome.outcome.unitsSold ||
            chosenReplay.grossProfit.compareTo(outcome.outcome.grossProfit) != 0
        ) {
            throw IntegrityError(
                "replay of ${outcome.decision.discount.percent}% for ${outcome.scenarioId} gave " +
                    "${chosenReplay.unitsSold} units / ${chosenReplay.grossProfit} but the outcome reported " +
                    "${outcome.outcome.unitsSold} units / ${outcome.outcome.grossProfit}",
            )
        }

        val profitByDiscount = profits.mapValues { (_, pair) -> pair.second }

        // Ties prefer the lower discount: giving away less for the same money is better advice.
        val best =
            Discount.entries
                .sortedWith(compareByDescending<Discount> { profitByDiscount.getValue(it) }.thenBy { it.percent })
                .first()

        val case =
            memory.findCase(caseId) ?: PromotionCase(
                caseId = caseId,
                scenarioId = outcome.scenarioId,
                simulatorVersion = outcome.simulatorVersion,
                scenario = outcome.scenario,
                chosenDiscount = outcome.decision.discount,
                chosenUnitsSold = outcome.outcome.unitsSold,
                chosenGrossProfit = outcome.outcome.grossProfit,
                profitByDiscount = profitByDiscount,
                bestDiscount = best,
            )

        if (!learningEnabled) {
            // Benchmark measurement still needs the oracle and the regret; it just must not learn
            // from the scenarios it is measuring on.
            log.info { "evaluated scenario_id=${outcome.scenarioId} regret=${case.regret} learning=disabled" }
            return EvaluationResult(case, emptyList(), learned = false)
        }

        memory.saveCase(case)
        val lessons =
            case.lessonKeys.map { key ->
                memory.linkCaseToLesson(case.caseId, key)
                Lesson.from(key, memory.casesFor(key)).also(memory::saveLesson)
            }
        log.info {
            "evaluated scenario_id=${outcome.scenarioId} chosen=${case.chosenDiscount.percent} " +
                "best=${case.bestDiscount.percent} regret=${case.regret} lessons=${lessons.size}"
        }
        return EvaluationResult(case, lessons, learned = true)
    }

    private companion object {
        private val log: Logger = Logger.getLogger(PromotionEvaluator::class.java.name)
    }
}
