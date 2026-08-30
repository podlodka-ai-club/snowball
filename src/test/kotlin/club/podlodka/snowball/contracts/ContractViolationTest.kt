package club.podlodka.snowball.contracts

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test

/**
 * A document the committed schema would reject must not parse into a model either. Everything here
 * mutates a committed example, so the fixtures cannot drift away from the contracts.
 */
class ContractViolationTest {
    private val mapper = ContractJson.mapper

    private fun scenarioTree(): ObjectNode =
        mapper.readTree(CommittedDocs.read(CommittedDocs.SCENARIO_EXAMPLE)) as ObjectNode

    private fun decisionTree(): ObjectNode =
        mapper.readTree(CommittedDocs.read(CommittedDocs.DECISION_EXAMPLE)) as ObjectNode

    private fun outcomeTree(): ObjectNode =
        mapper.readTree(CommittedDocs.read(CommittedDocs.OUTCOME_EXAMPLE)) as ObjectNode

    private inline fun <reified T> assertRejects(document: JsonNode) {
        assertThatExceptionOfType(JacksonException::class.java)
            .isThrownBy { mapper.readValue<T>(mapper.writeValueAsString(document)) }
    }

    @Test
    fun `a missing required field is rejected`() {
        assertRejects<PromotionScenarioEvent>(scenarioTree().apply { remove("scenario_id") })
        assertRejects<PromotionDecisionEvent>(decisionTree().apply { remove("decided_at") })
        assertRejects<PromotionOutcomeEvent>(outcomeTree().apply { remove("outcome") })
    }

    @Test
    fun `a missing required nested field is rejected`() {
        val document = scenarioTree()
        (document.get("scenario") as ObjectNode).remove("baseline_sales")

        assertRejects<PromotionScenarioEvent>(document)
    }

    @Test
    fun `a missing envelope constant is rejected`() {
        assertRejects<PromotionScenarioEvent>(scenarioTree().apply { remove("event_type") })
        assertRejects<PromotionDecisionEvent>(decisionTree().apply { remove("schema_version") })
        assertRejects<PromotionOutcomeEvent>(outcomeTree().apply { remove("simulator_version") })
    }

    @Test
    fun `an unknown property is rejected`() {
        assertRejects<PromotionScenarioEvent>(scenarioTree().apply { put("hidden_noise_factor", 1.0) })

        val document = outcomeTree()
        (document.get("outcome") as ObjectNode).put("oracle_best_discount", 30)
        assertRejects<PromotionOutcomeEvent>(document)
    }

    @Test
    fun `a discount outside the allowed set is rejected`() {
        val document = decisionTree()
        (document.get("decision") as ObjectNode).put("discount", 15)

        assertRejects<PromotionDecisionEvent>(document)
    }

    @Test
    fun `a value outside a closed enumeration is rejected`() {
        val badWeather = scenarioTree()
        (badWeather.get("scenario") as ObjectNode).put("weather", "snow")
        assertRejects<PromotionScenarioEvent>(badWeather)

        val badStockLevel = scenarioTree()
        (badStockLevel.get("scenario") as ObjectNode).put("stock_level", "low")
        assertRejects<PromotionScenarioEvent>(badStockLevel)

        val badDayType = scenarioTree()
        (badDayType.get("scenario") as ObjectNode).put("day_type", "holiday")
        assertRejects<PromotionScenarioEvent>(badDayType)

        val badMarketEvent = scenarioTree()
        (badMarketEvent.get("scenario") as ObjectNode).put("event_type", "festival")
        assertRejects<PromotionScenarioEvent>(badMarketEvent)
    }

    @Test
    fun `a wrong envelope constant is rejected`() {
        assertRejects<PromotionScenarioEvent>(scenarioTree().apply { put("event_type", "promotion.scenario.updated") })
        assertRejects<PromotionScenarioEvent>(scenarioTree().apply { put("schema_version", 2) })
        assertRejects<PromotionOutcomeEvent>(outcomeTree().apply { put("simulator_version", "v2") })
    }

    @Test
    fun `the committed examples themselves parse`() {
        assertThatCode {
            mapper.readValue<PromotionScenarioEvent>(CommittedDocs.read(CommittedDocs.SCENARIO_EXAMPLE))
            mapper.readValue<PromotionDecisionEvent>(CommittedDocs.read(CommittedDocs.DECISION_EXAMPLE))
            mapper.readValue<PromotionOutcomeEvent>(CommittedDocs.read(CommittedDocs.OUTCOME_EXAMPLE))
        }.doesNotThrowAnyException()
    }
}
