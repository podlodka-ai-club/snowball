package club.podlodka.snowball.domain

import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every committed example must survive parse-then-serialize unchanged. This is the guard that keeps
 * the hand-written models a faithful projection of the committed schemas.
 */
class ContractRoundTripTest {
    private val mapper = ContractJson.mapper

    private inline fun <reified T> assertRoundTrips(relativePath: String): T {
        val original = CommittedDocs.read(relativePath)
        val model: T = mapper.readValue(original)
        val serialized = mapper.writeValueAsString(model)

        assertThat(mapper.readTree(serialized))
            .describedAs("round trip of %s", relativePath)
            .isEqualTo(mapper.readTree(original))

        return model
    }

    @Test
    fun `scenario example round trips without losing fields`() {
        val event = assertRoundTrips<PromotionScenarioEvent>(CommittedDocs.SCENARIO_EXAMPLE)

        assertThat(event.eventType).isEqualTo(ScenarioEventType.SCENARIO_CREATED)
        assertThat(event.schemaVersion).isEqualTo(SchemaVersion.V1)
        assertThat(event.scenarioId).isEqualTo("SCN-20260718-LONDON_CENTRAL-ICE500")
        assertThat(event.source.type).isEqualTo("dataset")
        assertThat(event.scenario.storeId).isEqualTo("LONDON_CENTRAL")
        assertThat(event.scenario.stockLevel).isEqualTo(StockLevel.HIGH)
        assertThat(event.scenario.dayType).isEqualTo(DayType.WEEKEND)
        assertThat(event.scenario.weather).isEqualTo(Weather.HOT)
        assertThat(event.scenario.eventType).isEqualTo(MarketEvent.LOCAL_EVENT)
        assertThat(event.scenario.eventNote).isEqualTo("concert_nearby")
    }

    @Test
    fun `decision example round trips without losing fields`() {
        val event = assertRoundTrips<PromotionDecisionEvent>(CommittedDocs.DECISION_EXAMPLE)

        assertThat(event.eventType).isEqualTo(DecisionEventType.DECISION_CREATED)
        assertThat(event.decisionId).isEqualTo("DEC-SCN-20260718-LONDON_CENTRAL-ICE500")
        assertThat(event.scenarioId).isEqualTo("SCN-20260718-LONDON_CENTRAL-ICE500")
        assertThat(event.decision.discount).isEqualTo(Discount.TWENTY)
    }

    @Test
    fun `outcome example round trips without losing fields`() {
        val event = assertRoundTrips<PromotionOutcomeEvent>(CommittedDocs.OUTCOME_EXAMPLE)

        assertThat(event.eventType).isEqualTo(OutcomeEventType.OUTCOME_CREATED)
        assertThat(event.simulatorVersion).isEqualTo(SimulatorVersion.V1)
        assertThat(event.outcomeId).isEqualTo("OUT-DEC-SCN-20260718-LONDON_CENTRAL-ICE500")
        assertThat(event.outcome.unitsSold).isEqualTo(320)
        assertThat(event.outcome.grossProfit).isEqualTo(320.0)
    }

    @Test
    fun `the three events share one scenario payload`() {
        val scenario: PromotionScenarioEvent = mapper.readValue(CommittedDocs.read(CommittedDocs.SCENARIO_EXAMPLE))
        val decision: PromotionDecisionEvent = mapper.readValue(CommittedDocs.read(CommittedDocs.DECISION_EXAMPLE))
        val outcome: PromotionOutcomeEvent = mapper.readValue(CommittedDocs.read(CommittedDocs.OUTCOME_EXAMPLE))

        assertThat(decision.scenario).isEqualTo(scenario.scenario)
        assertThat(outcome.scenario).isEqualTo(scenario.scenario)
        assertThat(outcome.decision).isEqualTo(decision.decision)
    }

    @Test
    fun `the convenience constructors fill the envelope constants`() {
        val source = mapper.readValue<PromotionOutcomeEvent>(CommittedDocs.read(CommittedDocs.OUTCOME_EXAMPLE))

        val rebuilt =
            PromotionOutcomeEvent(
                outcomeId = source.outcomeId,
                decisionId = source.decisionId,
                scenarioId = source.scenarioId,
                simulatedAt = source.simulatedAt,
                scenario = source.scenario,
                decision = source.decision,
                outcome = source.outcome,
            )

        assertThat(rebuilt).isEqualTo(source)
    }

    @Test
    fun `absent optional fields are not serialized as nulls`() {
        val minimal =
            """
            {
              "event_type": "promotion.scenario.created",
              "schema_version": 1,
              "scenario_id": "SCN-MINIMAL",
              "generated_at": "2026-07-18T06:00:00Z",
              "source": { "type": "dataset", "reference": "fixture-0001" },
              "scenario": {
                "date": "2026-07-18",
                "store_id": "LONDON_CENTRAL",
                "sku_id": "ICE500",
                "category": "ice_cream",
                "price": 5.0,
                "cost": 3.0,
                "stock": 320,
                "baseline_sales": 100,
                "stock_level": "normal",
                "day_type": "weekday",
                "weather": "normal",
                "event_type": "none"
              }
            }
            """.trimIndent()

        val event: PromotionScenarioEvent = mapper.readValue(minimal)
        val serialized = mapper.writeValueAsString(event)

        assertThat(mapper.readTree(serialized)).isEqualTo(mapper.readTree(minimal))
        assertThat(serialized).doesNotContain("store_name", "sku_name", "temperature_c", "event_note")
    }
}
