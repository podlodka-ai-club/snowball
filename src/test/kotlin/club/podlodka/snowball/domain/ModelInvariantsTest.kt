package club.podlodka.snowball.domain

import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * What the models guarantee when they are built in Kotlin rather than parsed.
 *
 * Deserialization is only half the boundary. A model assembled by hand can just as easily publish
 * a document the committed schema would reject, and nothing upstream would catch it.
 */
class ModelInvariantsTest {
    private val mapper = ContractJson.mapper

    private fun scenarioEvent(): PromotionScenarioEvent =
        mapper.readValue(CommittedDocs.read(CommittedDocs.SCENARIO_EXAMPLE))

    private fun decisionEvent(): PromotionDecisionEvent =
        mapper.readValue(CommittedDocs.read(CommittedDocs.DECISION_EXAMPLE))

    @Test
    fun `a date outside the four-digit year range is rejected`() {
        val scenario = scenarioEvent().scenario

        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { scenario.copy(date = LocalDate.of(10_000, 7, 18)) }
    }

    @Test
    fun `an offset carrying seconds is rejected`() {
        val event = scenarioEvent()
        val withSecondsOffset = event.generatedAt.withOffsetSameLocal(ZoneOffset.ofHoursMinutesSeconds(1, 2, 3))

        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { event.copy(generatedAt = withSecondsOffset) }
    }

    @Test
    fun `a whole-minute offset is accepted and serializes as a valid date-time`() {
        val event = scenarioEvent()
        val shifted =
            event.copy(
                generatedAt = event.generatedAt.withOffsetSameInstant(ZoneOffset.ofHoursMinutes(1, 30)),
            )

        val serialized = mapper.readTree(mapper.writeValueAsString(shifted))

        assertThat(CommittedDocs.validate(CommittedDocs.SCENARIO_SCHEMA, serialized)).isEmpty()
    }

    @Test
    fun `the decision constructor copies identity and snapshot from the scenario event`() {
        val scenario = scenarioEvent()

        val decision =
            PromotionDecisionEvent(
                scenarioEvent = scenario,
                decisionId = "DEC-${scenario.scenarioId}",
                decidedAt = OffsetDateTime.parse("2026-07-18T06:00:01Z"),
                decision = PromotionDecision(Discount.TWENTY),
            )

        assertThat(decision.scenarioId).isEqualTo(scenario.scenarioId)
        assertThat(decision.scenario).isEqualTo(scenario.scenario)
        assertThat(decision.eventType).isEqualTo(DecisionEventType.DECISION_CREATED)
        assertThat(decision.schemaVersion).isEqualTo(SchemaVersion.V1)
        assertThat(
            CommittedDocs.validate(CommittedDocs.DECISION_SCHEMA, mapper.readTree(mapper.writeValueAsString(decision))),
        ).isEmpty()
    }

    @Test
    fun `the outcome constructor copies identity and snapshots from the decision event`() {
        val decision = decisionEvent()

        val outcome =
            PromotionOutcomeEvent(
                decisionEvent = decision,
                outcomeId = "OUT-${decision.decisionId}",
                simulatedAt = OffsetDateTime.parse("2026-07-18T06:00:02Z"),
                outcome = PromotionOutcome(unitsSold = 320, grossProfit = BigDecimal("320.00")),
            )

        assertThat(outcome.decisionId).isEqualTo(decision.decisionId)
        assertThat(outcome.scenarioId).isEqualTo(decision.scenarioId)
        assertThat(outcome.scenario).isEqualTo(decision.scenario)
        assertThat(outcome.decision).isEqualTo(decision.decision)
        assertThat(outcome.simulatorVersion).isEqualTo(SimulatorVersion.V1)
        assertThat(
            CommittedDocs.validate(CommittedDocs.OUTCOME_SCHEMA, mapper.readTree(mapper.writeValueAsString(outcome))),
        ).isEmpty()
    }

    @Test
    fun `equality is scale-sensitive because the models mirror documents`() {
        // Pinned deliberately. BigDecimal keeps the exact document value, and 320.0 and 320.00 are
        // different documents, so the models differ too. Domain code comparing amounts must use
        // compareTo; code comparing events must use the ids.
        val atScaleOne = PromotionOutcome(unitsSold = 320, grossProfit = BigDecimal("320.0"))
        val atScaleTwo = PromotionOutcome(unitsSold = 320, grossProfit = BigDecimal("320.00"))

        assertThat(atScaleOne).isNotEqualTo(atScaleTwo)
        assertThat(atScaleOne.grossProfit).isEqualByComparingTo(atScaleTwo.grossProfit)
        assertThat(setOf(atScaleOne, atScaleTwo)).hasSize(2)
    }

    @Test
    fun `an empty identifier is rejected on construction`() {
        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { scenarioEvent().copy(scenarioId = "") }

        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { PromotionOutcome(unitsSold = -1, grossProfit = BigDecimal.ZERO) }
    }
}
