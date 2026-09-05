package club.podlodka.snowball.memory

import club.podlodka.snowball.adapter.memory.XmemoryHttp
import club.podlodka.snowball.adapter.memory.XmemoryLearningMemory
import club.podlodka.snowball.adapter.simulator.SimulationEngine
import club.podlodka.snowball.application.PromotionEvaluator
import club.podlodka.snowball.application.SimulationService
import club.podlodka.snowball.config.XmemoryConfig
import club.podlodka.snowball.domain.CommittedDocs
import club.podlodka.snowball.domain.ContractJson
import club.podlodka.snowball.domain.DayType
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.Lesson
import club.podlodka.snowball.domain.LessonKey
import club.podlodka.snowball.domain.LessonScope
import club.podlodka.snowball.domain.PromotionDecision
import club.podlodka.snowball.domain.PromotionDecisionEvent
import club.podlodka.snowball.domain.PromotionOutcomeEvent
import club.podlodka.snowball.domain.PromotionScenario
import club.podlodka.snowball.port.LearningMemory
import club.podlodka.snowball.port.OutcomeSink
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * What survives a restart, and what a repeat run must not change.
 *
 * These are the two claims the sprint actually rests on - memory outlives the process, and the
 * agent's behaviour follows from accumulated evidence - so they are checked against stored state
 * rather than against a recorded response. The store is a stub: the shared quota is measured in
 * model tokens, and a real run costs about a third of the daily allowance.
 */
class DurableLearningTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-18T06:00:02Z"), ZoneOffset.UTC)
    private val engine = SimulationEngine()
    private lateinit var store: StubXmemoryServer

    @BeforeEach
    fun start() {
        store = StubXmemoryServer()
        store.start()
    }

    @AfterEach
    fun stop() = store.stop()

    /** A fresh client over the same store, standing in for the next run of the process. */
    private fun connect(): XmemoryLearningMemory =
        XmemoryLearningMemory(
            XmemoryHttp(XmemoryConfig(baseUrl = store.baseUrl, instanceId = "stub", apiKey = "stub-key")),
        )

    private val example: PromotionScenario =
        ContractJson.mapper
            .readValue<PromotionDecisionEvent>(CommittedDocs.read(CommittedDocs.DECISION_EXAMPLE))
            .scenario

    private fun outcome(
        discount: Discount,
        scenario: PromotionScenario = example,
        scenarioId: String = "SCN-DURABLE",
    ): PromotionOutcomeEvent {
        val input =
            PromotionDecisionEvent(
                decisionId = "DEC-$scenarioId",
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
    fun `a lesson written by one process is read back by the next`() {
        val learned = PromotionEvaluator(engine, connect()).evaluate(outcome(Discount.TEN))
        val key = learned.lessons.first().key

        // Nothing of the first client survives: new HTTP client, new adapter, same store.
        val reread = connect().lesson(key)

        assertThat(reread).isNotNull
        assertThat(reread!!.recommendedDiscount).isEqualTo(learned.lessons.first().recommendedDiscount)
        assertThat(reread.evidenceCount).isEqualTo(1)
    }

    @Test
    fun `one outcome writes one case and the whole cascade of lessons`() {
        val result = PromotionEvaluator(engine, connect()).evaluate(outcome(Discount.TEN))

        assertThat(store.objectsOf("PromotionCase")).hasSize(1)
        // Three levels of key per scope, and one evidence link from the case to each.
        assertThat(store.objectsOf("Lesson")).hasSize(6)
        assertThat(store.linkCount("lesson_evidence")).isEqualTo(6)
        assertThat(result.lessons).hasSize(6)
    }

    @Test
    fun `replaying the same scenario does not count its evidence twice`() {
        // The resume path. A run interrupted mid-scenario re-processes it, and if that inflated
        // the evidence count, one scenario would carry double weight in every lesson it supports -
        // silently, and more so the more often a run was interrupted.
        val first = PromotionEvaluator(engine, connect()).evaluate(outcome(Discount.TEN))
        val second = PromotionEvaluator(engine, connect()).evaluate(outcome(Discount.TEN))

        assertThat(second.lessons.map { it.evidenceCount }).containsOnly(1)
        assertThat(second.lessons).isEqualTo(first.lessons)
        assertThat(store.objectsOf("PromotionCase")).hasSize(1)
        assertThat(store.linkCount("lesson_evidence")).isEqualTo(6)
    }

    @Test
    fun `a product is written once, however many cases and runs mention it`() {
        // The SKU is the record hundreds of scenarios share. The second scenario for it, and the
        // next process, must find it there and update it - a refused duplicate would end a
        // training run on its second scenario.
        PromotionEvaluator(engine, connect()).evaluate(outcome(Discount.TEN))
        val repriced = example.copy(price = BigDecimal("5.50"), cost = BigDecimal("3.10"))
        PromotionEvaluator(engine, connect()).evaluate(outcome(Discount.TEN, repriced, "SCN-DURABLE-2"))

        val skus = store.objectsOf("SKU")
        assertThat(skus).hasSize(1)
        val sku = skus.single()
        assertThat(sku.getValue("sku_id").asText()).isEqualTo("ICE500")
        assertThat(sku.getValue("name").asText()).isEqualTo("Ice Cream 500ml")
        assertThat(sku.getValue("category").asText()).isEqualTo("ice_cream")
        // The later scenario's economics, not the first ones: the record follows what was last seen.
        assertThat(sku.getValue("base_price").decimalValue()).isEqualByComparingTo("5.50")
        assertThat(sku.getValue("cost").decimalValue()).isEqualByComparingTo("3.10")
        assertThat(store.objectsOf("PromotionCase")).hasSize(2)
    }

    @Test
    fun `each case points at the product it tested`() {
        PromotionEvaluator(engine, connect()).evaluate(outcome(Discount.TEN))
        val other = example.copy(skuId = "MEAT1", skuName = "Meat Pack", category = "meat")
        PromotionEvaluator(engine, connect()).evaluate(outcome(Discount.TEN, other, "SCN-DURABLE-MEAT"))

        assertThat(store.links("case_sku")).containsExactlyInAnyOrder(
            mapOf("case" to "CASE-v1-SCN-DURABLE", "sku" to "ICE500"),
            mapOf("case" to "CASE-v1-SCN-DURABLE-MEAT", "sku" to "MEAT1"),
        )
    }

    @Test
    fun `a category lesson covers every product of the category the memory knows`() {
        // Two products of one category, recorded by two different processes on different days.
        // The second process never wrote ICE500 itself, and the first never wrote a weekday
        // lesson, so a weekday category lesson can reach ICE500 only through the store.
        PromotionEvaluator(engine, connect()).evaluate(outcome(Discount.TEN))
        val smaller = example.copy(skuId = "ICE250", skuName = "Ice Cream 250ml", dayType = DayType.WEEKDAY)
        val second = PromotionEvaluator(engine, connect()).evaluate(outcome(Discount.TEN, smaller, "SCN-DURABLE-250"))

        val scoped = store.links("lesson_sku_scope")

        fun skusOf(key: LessonKey) = scoped.filter { it["lesson"] == key.wire }.map { it.getValue("sku") }

        val categoryKeys = second.lessons.map { it.key }.filter { it.scope == LessonScope.CATEGORY }
        assertThat(categoryKeys).hasSize(3)
        categoryKeys.forEach { key -> assertThat(skusOf(key)).containsExactlyInAnyOrder("ICE500", "ICE250") }
        // A SKU lesson speaks for its one product and no other.
        val skuKeys = second.lessons.map { it.key }.filter { it.scope == LessonScope.SKU }
        assertThat(skuKeys).hasSize(3)
        skuKeys.forEach { key -> assertThat(skusOf(key)).containsExactly("ICE250") }
    }

    @Test
    fun `seeding writes every bucket in batches and can be run twice`() {
        // Rebuilding buckets a past run never created. The service applies a batch atomically, so
        // the second pass collides with everything the first wrote - and has to end in the same
        // state rather than in an error or in doubled evidence.
        val memory = connect()
        val learned = PromotionEvaluator(engine, memory).evaluate(outcome(Discount.TEN))
        val extra =
            learned.lessons.map { it.key }.map { key ->
                Lesson(key, Discount.THIRTY, 7, BigDecimal("9.00"), BigDecimal("0.90"), "seeded")
            }
        val links = extra.map { "CASE-v1-SCN-DURABLE" to it.key }

        memory.seed(extra, links)
        val afterFirst = store.objectsOf("Lesson").size to store.linkCount("lesson_evidence")
        memory.seed(extra, links)

        assertThat(store.objectsOf("Lesson")).hasSize(afterFirst.first)
        assertThat(store.linkCount("lesson_evidence")).isEqualTo(afterFirst.second)
        // The seeded aggregate replaced what the run had computed, on the same keys.
        assertThat(memory.lesson(extra.first().key)?.recommendedDiscount).isEqualTo(Discount.THIRTY)
        assertThat(memory.lesson(extra.first().key)?.evidenceCount).isEqualTo(7)
    }

    @Test
    fun `with learning disabled the run writes nothing at all`() {
        // What keeps the benchmark honest: the clean arm must not teach itself while being
        // measured, and the trained arm must not learn from the scenarios it is scored on.
        val memory: LearningMemory = connect()

        val result = PromotionEvaluator(engine, memory, learningEnabled = false).evaluate(outcome(Discount.TEN))

        assertThat(result.learned).isFalse()
        assertThat(result.lessons).isEmpty()
        assertThat(store.writes).isZero()
        assertThat(store.objectsOf("PromotionCase")).isEmpty()
        // The regret is still computed - measurement needs it, learning is what is switched off.
        assertThat(result.case.regret).isNotNull()
    }
}
