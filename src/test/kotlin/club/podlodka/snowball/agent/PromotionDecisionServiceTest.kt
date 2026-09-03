package club.podlodka.snowball.agent

import club.podlodka.snowball.adapter.memory.InMemoryDecisionJournal
import club.podlodka.snowball.adapter.memory.InMemoryLearningMemory
import club.podlodka.snowball.adapter.model.PromptBuilder
import club.podlodka.snowball.application.DecisionSource
import club.podlodka.snowball.application.MemoryStatus
import club.podlodka.snowball.application.PromotionDecisionService
import club.podlodka.snowball.domain.CommittedDocs
import club.podlodka.snowball.domain.ContractJson
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.Lesson
import club.podlodka.snowball.domain.LessonKey
import club.podlodka.snowball.domain.PromotionScenario
import club.podlodka.snowball.domain.PromotionScenarioEvent
import club.podlodka.snowball.port.DecisionModel
import club.podlodka.snowball.port.DecisionSink
import club.podlodka.snowball.port.ModelDecision
import club.podlodka.snowball.port.SimulationPort
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PromotionDecisionServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-18T06:00:01Z"), ZoneOffset.UTC)

    private fun scenarioEvent(): PromotionScenarioEvent =
        ContractJson.mapper.readValue(CommittedDocs.read(CommittedDocs.SCENARIO_EXAMPLE))

    /** Records what it was shown, so the prompt itself can be asserted on. */
    private class SpyModel(
        private val answer: Discount?,
        override val modelId: String = "stub-model",
    ) : DecisionModel {
        var calls = 0
        var lastLessons: List<Lesson> = emptyList()
        var lastPrompt: String = ""

        override fun choose(
            scenario: PromotionScenario,
            lessons: List<Lesson>,
        ): ModelDecision? {
            calls += 1
            lastLessons = lessons
            lastPrompt = PromptBuilder.user(scenario, lessons)
            return answer?.let { ModelDecision(it, modelId, """{"discount": ${it.percent}}""") }
        }
    }

    private fun lesson(
        key: LessonKey,
        discount: Discount,
        confidence: String = "0.80",
        evidence: Int = 5,
    ) = Lesson(key, discount, evidence, BigDecimal("10.00"), BigDecimal(confidence), "because $discount")

    private fun service(
        model: DecisionModel,
        memory: InMemoryLearningMemory = InMemoryLearningMemory(),
        journal: InMemoryDecisionJournal = InMemoryDecisionJournal(),
        sink: DecisionSink = DecisionSink { },
    ) = PromotionDecisionService(memory, model, journal, sink, clock)

    @Test
    fun `the model choice becomes the decision`() {
        val model = SpyModel(Discount.TWENTY)
        val published = mutableListOf<String>()

        val outcome = service(model, sink = DecisionSink { published += it.decisionId }).decide(scenarioEvent())

        assertThat(outcome.decision.decision.discount).isEqualTo(Discount.TWENTY)
        assertThat(outcome.source).isEqualTo(DecisionSource.MODEL)
        assertThat(published).containsExactly("DEC-${scenarioEvent().scenarioId}")
    }

    @Test
    fun `the decision satisfies its committed contract`() {
        val outcome = service(SpyModel(Discount.TEN)).decide(scenarioEvent())

        val document = ContractJson.mapper.readTree(ContractJson.mapper.writeValueAsString(outcome.decision))
        assertThat(CommittedDocs.validate(CommittedDocs.DECISION_SCHEMA, document)).isEmpty()
        assertThat(outcome.decision.scenario).isEqualTo(scenarioEvent().scenario)
    }

    @Test
    fun `two failed attempts fall back to zero, counted as a fallback`() {
        // The fallback must be visible in the record. A run where the model kept failing would
        // otherwise read as a run where it kept choosing 0%, and the benchmark would be measuring
        // an outage instead of a policy.
        val model = SpyModel(null)

        val outcome = service(model).decide(scenarioEvent())

        assertThat(model.calls).isEqualTo(2)
        assertThat(outcome.decision.decision.discount).isEqualTo(Discount.NONE)
        assertThat(outcome.source).isEqualTo(DecisionSource.FALLBACK)
    }

    @Test
    fun `a completed scenario never reaches memory or the model again`() {
        val model = SpyModel(Discount.THIRTY)
        val journal = InMemoryDecisionJournal()
        val agent = service(model, journal = journal)

        agent.decide(scenarioEvent())
        val callsAfterFirst = model.calls
        val repeat = agent.decide(scenarioEvent())

        assertThat(model.calls).isEqualTo(callsAfterFirst)
        assertThat(repeat.source).isEqualTo(DecisionSource.JOURNAL)
        assertThat(repeat.decision.decision.discount).isEqualTo(Discount.THIRTY)
    }

    @Test
    fun `lessons for this scenario reach the prompt, exact SKU first`() {
        val memory = InMemoryLearningMemory()
        val buckets = LessonKey.bucketsFor(scenarioEvent().scenario)
        memory.saveLesson(lesson(buckets[1], Discount.TEN, confidence = "0.95"))
        memory.saveLesson(lesson(buckets[0], Discount.TWENTY, confidence = "0.50"))
        val model = SpyModel(Discount.TWENTY)

        val outcome = service(model, memory = memory).decide(scenarioEvent())

        assertThat(outcome.memoryStatus).isEqualTo(MemoryStatus.USED)
        // The SKU bucket outranks the category one even with lower confidence: advice about this
        // product beats advice about its family.
        assertThat(model.lastLessons.first().key).isEqualTo(buckets[0])
        assertThat(outcome.lessonsUsed).containsExactly(buckets[0], buckets[1])
    }

    @Test
    fun `an empty memory renders in the same template, not a different instruction`() {
        // If the wording changed with memory, the measured delta would partly be the delta between
        // two prompts and the experiment would prove nothing.
        val withMemory = InMemoryLearningMemory()
        withMemory.saveLesson(lesson(LessonKey.bucketsFor(scenarioEvent().scenario).first(), Discount.TWENTY))
        val cold = SpyModel(Discount.NONE)
        val warm = SpyModel(Discount.TWENTY)

        service(cold).decide(scenarioEvent())
        service(warm, memory = withMemory).decide(scenarioEvent())

        val coldPrompt = cold.lastPrompt
        val warmPrompt = warm.lastPrompt
        assertThat(coldPrompt).contains("Lessons from memory: none.")
        val coldSkeleton = coldPrompt.substringBefore("Lessons from memory:")
        val warmSkeleton = warmPrompt.substringBefore("Lessons from memory:")
        assertThat(warmSkeleton).isEqualTo(coldSkeleton)
    }

    @Test
    fun `the prompt carries no ground truth`() {
        val model = SpyModel(Discount.TEN)

        service(model).decide(scenarioEvent())

        assertThat(model.lastPrompt.lowercase())
            .doesNotContain("noise", "coefficient", "oracle", "regret", "counterfactual", "profit_")
    }

    @Test
    fun `the agent cannot reach simulation`() {
        val injected =
            PromotionDecisionService::class.java.declaredConstructors
                .flatMap { it.parameterTypes.asIterable() }
                .map { it.name }

        assertThat(injected).doesNotContain(SimulationPort::class.java.name)
    }

    @Test
    fun `an unreachable memory does not stop the decision`() {
        val broken =
            object : InMemoryLearningMemory() {
                override fun lesson(key: LessonKey) = throw IllegalStateException("memory down")
            }
        val model = SpyModel(Discount.TEN)

        val outcome = service(model, memory = broken).decide(scenarioEvent())

        assertThat(outcome.memoryStatus).isEqualTo(MemoryStatus.UNAVAILABLE)
        assertThat(outcome.source).isEqualTo(DecisionSource.MODEL)
    }
}
