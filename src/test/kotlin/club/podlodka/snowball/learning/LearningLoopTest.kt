package club.podlodka.snowball.learning

import club.podlodka.snowball.adapter.context.DeterministicContextEnricher
import club.podlodka.snowball.adapter.memory.EvaluatingOutcomeSink
import club.podlodka.snowball.adapter.memory.InMemoryLearningMemory
import club.podlodka.snowball.adapter.simulator.SimulationEngine
import club.podlodka.snowball.adapter.source.DatasetBaselineSource
import club.podlodka.snowball.application.PromotionEvaluator
import club.podlodka.snowball.application.ScenarioGenerationService
import club.podlodka.snowball.application.SimulationService
import club.podlodka.snowball.domain.DatasetSplit
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.LessonScope
import club.podlodka.snowball.domain.PromotionDecision
import club.podlodka.snowball.domain.PromotionDecisionEvent
import club.podlodka.snowball.domain.PromotionScenarioEvent
import club.podlodka.snowball.port.ScenarioPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The thin end-to-end path, minus the agent: real fixture rows become scenarios, a fixed policy
 * decides, the simulator produces ground truth, and the evaluator turns it into lessons.
 *
 * This is what the project is ultimately judged on - not that each part works, but that the loop
 * closes and memory accumulates. The agent is the only piece still missing, and it replaces the
 * fixed policy used here.
 */
class LearningLoopTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-18T06:00:00Z"), ZoneOffset.UTC)

    private fun run(
        limit: Int,
        policy: (PromotionScenarioEvent) -> Discount,
        learningEnabled: Boolean = true,
    ): InMemoryLearningMemory {
        val memory = InMemoryLearningMemory()
        val engine = SimulationEngine()
        val sink = EvaluatingOutcomeSink(PromotionEvaluator(engine, memory, learningEnabled))
        val simulator = SimulationService(engine, sink, clock)

        var seen = 0
        val publisher =
            ScenarioPublisher { scenario: PromotionScenarioEvent ->
                if (seen < limit) {
                    seen += 1
                    simulator.simulate(
                        PromotionDecisionEvent(
                            scenarioEvent = scenario,
                            decisionId = "DEC-${scenario.scenarioId}",
                            decidedAt = OffsetDateTime.now(clock),
                            decision = PromotionDecision(policy(scenario)),
                        ),
                    )
                }
            }

        ScenarioGenerationService(
            baselineSource =
                DatasetBaselineSource {
                    javaClass.getResourceAsStream("/fixtures/baseline.csv")!!.reader()
                },
            contextEnricher = DeterministicContextEnricher(),
            publisher = publisher,
            clock = clock,
        ).generate(DatasetSplit.TRAINING)

        return memory
    }

    @Test
    fun `the loop closes and memory accumulates`() {
        val memory = run(limit = 40, policy = { Discount.TEN })

        assertThat(memory.caseCount).isEqualTo(40)
        assertThat(memory.allLessons).isNotEmpty()
        assertThat(memory.allLessons.map { it.key.scope })
            .contains(LessonScope.SKU, LessonScope.CATEGORY)
        assertThat(memory.allLessons).allSatisfy { lesson ->
            assertThat(lesson.evidenceCount).isPositive()
            assertThat(lesson.rationale).isNotBlank()
        }
    }

    @Test
    fun `category lessons gather more evidence than single-SKU ones`() {
        // The reason for two buckets: a category generalises across its SKUs, so it should
        // accumulate evidence faster and be usable sooner than any one product's bucket.
        val memory = run(limit = 60, policy = { Discount.TWENTY })

        val bySku = memory.allLessons.filter { it.key.scope == LessonScope.SKU }
        val byCategory = memory.allLessons.filter { it.key.scope == LessonScope.CATEGORY }
        assertThat(byCategory.maxOf { it.evidenceCount })
            .isGreaterThanOrEqualTo(bySku.maxOf { it.evidenceCount })
    }

    @Test
    fun `a fixed policy leaves measurable regret for the agent to beat`() {
        // If always choosing one action were already optimal there would be nothing to learn and
        // no delta to show. This is the calibration claim, checked on the real fixture end to end.
        val memory = run(limit = 60, policy = { Discount.NONE })

        assertThat(memory.allLessons).isNotEmpty()
        assertThat(memory.allLessons.count { it.recommendedDiscount != Discount.NONE })
            .describedAs("lessons that disagree with always-0%%")
            .isPositive()
    }

    @Test
    fun `a benchmark run measures without learning`() {
        val memory = run(limit = 20, policy = { Discount.TEN }, learningEnabled = false)

        assertThat(memory.caseCount).isZero()
        assertThat(memory.allLessons).isEmpty()
    }
}
