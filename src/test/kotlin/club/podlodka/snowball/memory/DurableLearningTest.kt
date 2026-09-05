package club.podlodka.snowball.memory

import club.podlodka.snowball.adapter.memory.XmemoryHttp
import club.podlodka.snowball.adapter.memory.XmemoryLearningMemory
import club.podlodka.snowball.adapter.simulator.SimulationEngine
import club.podlodka.snowball.application.PromotionEvaluator
import club.podlodka.snowball.application.SimulationService
import club.podlodka.snowball.config.XmemoryConfig
import club.podlodka.snowball.domain.CommittedDocs
import club.podlodka.snowball.domain.ContractJson
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.PromotionDecision
import club.podlodka.snowball.domain.PromotionDecisionEvent
import club.podlodka.snowball.domain.PromotionOutcomeEvent
import club.podlodka.snowball.port.LearningMemory
import club.podlodka.snowball.port.OutcomeSink
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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

    private fun outcome(discount: Discount): PromotionOutcomeEvent {
        val decision: PromotionDecisionEvent =
            ContractJson.mapper.readValue(
                CommittedDocs.read(CommittedDocs.DECISION_EXAMPLE),
            )
        val input =
            PromotionDecisionEvent(
                decisionId = "DEC-SCN-DURABLE",
                scenarioId = "SCN-DURABLE",
                decidedAt = OffsetDateTime.now(clock),
                scenario = decision.scenario,
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
    fun `one outcome writes one case and exactly two lessons`() {
        val result = PromotionEvaluator(engine, connect()).evaluate(outcome(Discount.TEN))

        assertThat(store.objectsOf("PromotionCase")).hasSize(1)
        assertThat(store.objectsOf("Lesson")).hasSize(2)
        assertThat(store.linkCount()).isEqualTo(2)
        assertThat(result.lessons).hasSize(2)
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
        assertThat(store.linkCount()).isEqualTo(2)
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
