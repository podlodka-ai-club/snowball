package club.podlodka.snowball.memory

import club.podlodka.snowball.adapter.memory.CachingEvidenceMemory
import club.podlodka.snowball.adapter.memory.InMemoryLearningMemory
import club.podlodka.snowball.domain.CaseEvidence
import club.podlodka.snowball.domain.CommittedDocs
import club.podlodka.snowball.domain.ContractJson
import club.podlodka.snowball.domain.DayType
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.LessonKey
import club.podlodka.snowball.domain.LessonScope
import club.podlodka.snowball.domain.PromotionCase
import club.podlodka.snowball.domain.PromotionScenario
import club.podlodka.snowball.domain.PromotionScenarioEvent
import club.podlodka.snowball.domain.SimulatorVersion
import club.podlodka.snowball.domain.StockLevel
import club.podlodka.snowball.domain.Weather
import club.podlodka.snowball.port.LearningMemory
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CachingEvidenceMemoryTest {
    private val key = LessonKey(LessonScope.SKU, "ICE500", DayType.WEEKEND, Weather.HOT, StockLevel.HIGH)

    private fun evidence(id: String) =
        CaseEvidence(id, Discount.entries.associateWith { BigDecimal(it.percent) }, Discount.TEN)

    @Test
    fun `recorded evidence is loaded once, not once per lesson`() {
        var loads = 0
        val memory =
            CachingEvidenceMemory(InMemoryLearningMemory()) {
                loads++
                mapOf(key.wire to listOf(evidence("CASE-old")))
            }

        repeat(3) { memory.casesFor(key) }

        assertThat(loads).isEqualTo(1)
        assertThat(memory.casesFor(key).map { it.caseId }).containsExactly("CASE-old")
    }

    @Test
    fun `a case linked in this run joins the evidence without another read`() {
        var loads = 0
        val memory =
            CachingEvidenceMemory(InMemoryLearningMemory()) {
                loads++
                mapOf(key.wire to listOf(evidence("CASE-old")))
            }

        memory.saveCase(case("CASE-new"))
        memory.linkCaseToLesson("CASE-new", key)

        // Both the earlier evidence and this run's own case, from a single load.
        assertThat(memory.casesFor(key).map { it.caseId }).containsExactlyInAnyOrder("CASE-old", "CASE-new")
        assertThat(loads).isEqualTo(1)
    }

    @Test
    fun `re-linking the same case does not double-count it`() {
        // A resumed run re-processes the scenario it was interrupted on. Counting its case twice
        // would weight that one scenario double in every lesson it supports.
        val memory = CachingEvidenceMemory(InMemoryLearningMemory()) { emptyMap() }

        memory.saveCase(case("CASE-new"))
        repeat(2) { memory.linkCaseToLesson("CASE-new", key) }

        assertThat(memory.casesFor(key)).hasSize(1)
    }

    @Test
    fun `writes still reach the durable memory`() {
        // The cache is an aggregation shortcut, not a place to keep results: what the agent reads
        // back in a later run has to come from the durable store.
        val durable: LearningMemory = InMemoryLearningMemory()
        val memory = CachingEvidenceMemory(durable) { emptyMap() }

        memory.saveCase(case("CASE-new"))
        memory.linkCaseToLesson("CASE-new", key)

        assertThat(durable.findCase("CASE-new")).isNotNull
        assertThat(durable.casesFor(key).map { it.caseId }).containsExactly("CASE-new")
    }

    private fun scenario(): PromotionScenario =
        ContractJson.mapper
            .readValue<PromotionScenarioEvent>(CommittedDocs.read(CommittedDocs.SCENARIO_EXAMPLE))
            .scenario

    private fun case(id: String): PromotionCase =
        PromotionCase(
            caseId = id,
            scenarioId = "SCN-1",
            simulatorVersion = SimulatorVersion.V1,
            scenario = scenario(),
            chosenDiscount = Discount.TEN,
            chosenUnitsSold = 10,
            chosenGrossProfit = BigDecimal("10.00"),
            profitByDiscount = Discount.entries.associateWith { BigDecimal(it.percent) },
            bestDiscount = Discount.TEN,
        )
}
