package club.podlodka.snowball.learning

import club.podlodka.snowball.adapter.memory.InMemoryLearningMemory
import club.podlodka.snowball.adapter.simulator.SimulationEngine
import club.podlodka.snowball.application.IntegrityError
import club.podlodka.snowball.application.PromotionEvaluator
import club.podlodka.snowball.application.SimulationService
import club.podlodka.snowball.domain.CommittedDocs
import club.podlodka.snowball.domain.ContractJson
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.LessonScope
import club.podlodka.snowball.domain.PromotionDecision
import club.podlodka.snowball.domain.PromotionDecisionEvent
import club.podlodka.snowball.domain.PromotionOutcome
import club.podlodka.snowball.domain.PromotionOutcomeEvent
import club.podlodka.snowball.domain.PromotionScenario
import club.podlodka.snowball.port.OutcomeSink
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class PromotionEvaluatorTest {
    private val engine = SimulationEngine()
    private val clock = Clock.fixed(Instant.parse("2026-07-18T06:00:02Z"), ZoneOffset.UTC)

    private fun decision(): PromotionDecisionEvent =
        ContractJson.mapper.readValue(CommittedDocs.read(CommittedDocs.DECISION_EXAMPLE))

    /** A real outcome, produced by the simulator rather than hand-written. */
    private fun outcomeFor(
        scenario: PromotionScenario,
        discount: Discount,
        scenarioId: String = "SCN-TEST",
    ): PromotionOutcomeEvent {
        val input =
            PromotionDecisionEvent(
                decisionId = "DEC-$scenarioId-${discount.percent}",
                scenarioId = scenarioId,
                decidedAt = OffsetDateTime.now(clock),
                scenario = scenario,
                decision = PromotionDecision(discount),
            )
        var captured: PromotionOutcomeEvent? = null
        SimulationService(engine, OutcomeSink { captured = it }, clock).simulate(input)
        return captured!!
    }

    @Test
    fun `every allowed action is replayed and compared`() {
        val memory = InMemoryLearningMemory()
        val outcome = outcomeFor(decision().scenario, Discount.TWENTY)

        val result = PromotionEvaluator(engine, memory).evaluate(outcome)

        assertThat(result.case.profitByDiscount.keys).containsExactlyInAnyOrderElementsOf(Discount.entries)
        assertThat(result.case.bestGrossProfit)
            .isEqualByComparingTo(
                result.case.profitByDiscount.values
                    .max(),
            )
        assertThat(result.case.regret).isGreaterThanOrEqualTo(BigDecimal.ZERO)
    }

    @Test
    fun `a case teaches exactly two buckets, its SKU and its category`() {
        val memory = InMemoryLearningMemory()

        val result = PromotionEvaluator(engine, memory).evaluate(outcomeFor(decision().scenario, Discount.TEN))

        assertThat(result.lessons).hasSize(2)
        assertThat(result.lessons.map { it.key.scope })
            .containsExactlyInAnyOrder(LessonScope.SKU, LessonScope.CATEGORY)
        assertThat(result.lessons.map { it.key.wire })
            .allSatisfy { assertThat(it).contains("store:any", "event:any") }
    }

    @Test
    fun `a chosen action that does not reproduce is refused and teaches nothing`() {
        // If replay disagrees with the reported outcome, something drifted - the scenario, the
        // version, the arithmetic. Learning from it would record a fact that never happened.
        val memory = InMemoryLearningMemory()
        val tampered =
            outcomeFor(decision().scenario, Discount.TWENTY).let {
                it.copy(outcome = PromotionOutcome(it.outcome.unitsSold + 1, it.outcome.grossProfit))
            }

        assertThatExceptionOfType(IntegrityError::class.java)
            .isThrownBy { PromotionEvaluator(engine, memory).evaluate(tampered) }

        assertThat(memory.caseCount).isZero()
        assertThat(memory.allLessons).isEmpty()
    }

    @Test
    fun `evaluating the same promotion twice does not duplicate evidence`() {
        val memory = InMemoryLearningMemory()
        val evaluator = PromotionEvaluator(engine, memory)
        val outcome = outcomeFor(decision().scenario, Discount.TWENTY)

        evaluator.evaluate(outcome)
        val second = evaluator.evaluate(outcome)

        assertThat(memory.caseCount).isEqualTo(1)
        assertThat(second.lessons).allSatisfy { assertThat(it.evidenceCount).isEqualTo(1) }
    }

    @Test
    fun `learning can be disabled while oracle and regret still work`() {
        // The benchmark has to measure regret on scenarios it must not learn from, or it becomes
        // an elaborate way of asking the agent whether it remembers its homework.
        val memory = InMemoryLearningMemory()

        val result =
            PromotionEvaluator(engine, memory, learningEnabled = false)
                .evaluate(outcomeFor(decision().scenario, Discount.THIRTY))

        assertThat(result.learned).isFalse()
        assertThat(result.case.bestDiscount).isNotNull()
        assertThat(result.case.regret).isNotNull()
        assertThat(memory.caseCount).isZero()
        assertThat(memory.allLessons).isEmpty()
    }

    @Test
    fun `evidence accumulates and the recommendation follows the numbers`() {
        // The point of the whole exercise: several cases in one bucket, and the lesson recommends
        // the action with the best aggregate profit rather than the one most recently chosen.
        val memory = InMemoryLearningMemory()
        val evaluator = PromotionEvaluator(engine, memory)
        val scenario = decision().scenario.copy(stock = 100_000)

        val chosen = listOf(Discount.NONE, Discount.THIRTY, Discount.TEN)
        chosen.forEachIndexed { index, discount ->
            evaluator.evaluate(outcomeFor(scenario, discount, scenarioId = "SCN-ACC-$index"))
        }

        val skuLesson = memory.allLessons.single { it.key.scope == LessonScope.SKU }
        assertThat(skuLesson.evidenceCount).isEqualTo(3)
        val expected =
            Discount.entries.maxByOrNull { discount ->
                memory.casesFor(skuLesson.key).sumOf { it.profitByDiscount.getValue(discount).toDouble() }
            }
        assertThat(skuLesson.recommendedDiscount).isEqualTo(expected)
        assertThat(skuLesson.rationale).contains("3 evaluated cases", "highest mean gross profit")
    }
}
