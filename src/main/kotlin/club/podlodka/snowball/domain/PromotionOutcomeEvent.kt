package club.podlodka.snowball.domain

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import java.time.OffsetDateTime

/**
 * The `event_type` envelope constant of `promotion-outcome-v1.schema.json`.
 */
enum class OutcomeEventType(
    @get:JsonValue val wire: String,
) {
    OUTCOME_CREATED("promotion.outcome.created"),
}

/**
 * The `simulator_version` envelope constant of `promotion-outcome-v1.schema.json`.
 */
enum class SimulatorVersion(
    @get:JsonValue val wire: String,
) {
    V1("v1"),
}

/**
 * `promotion-outcome-v1.schema.json`: the chosen-action business outcome the Market Simulator hands
 * to the Evaluator and Learner.
 */
data class PromotionOutcomeEvent(
    @JsonProperty("event_type", required = true)
    val eventType: OutcomeEventType,
    @JsonProperty("schema_version", required = true)
    val schemaVersion: SchemaVersion,
    @JsonProperty("outcome_id", required = true)
    val outcomeId: String,
    @JsonProperty("decision_id", required = true)
    val decisionId: String,
    @JsonProperty("scenario_id", required = true)
    val scenarioId: String,
    @JsonProperty("simulated_at", required = true)
    val simulatedAt: OffsetDateTime,
    @JsonProperty("simulator_version", required = true)
    val simulatorVersion: SimulatorVersion,
    @JsonProperty("scenario", required = true)
    val scenario: PromotionScenario,
    @JsonProperty("decision", required = true)
    val decision: PromotionDecision,
    @JsonProperty("outcome", required = true)
    val outcome: PromotionOutcome,
) {
    init {
        require(outcomeId.isNotEmpty()) { "outcome_id must not be empty" }
        require(decisionId.isNotEmpty()) { "decision_id must not be empty" }
        require(scenarioId.isNotEmpty()) { "scenario_id must not be empty" }
    }

    constructor(
        outcomeId: String,
        decisionId: String,
        scenarioId: String,
        simulatedAt: OffsetDateTime,
        scenario: PromotionScenario,
        decision: PromotionDecision,
        outcome: PromotionOutcome,
    ) : this(
        eventType = OutcomeEventType.OUTCOME_CREATED,
        schemaVersion = SchemaVersion.V1,
        outcomeId = outcomeId,
        decisionId = decisionId,
        scenarioId = scenarioId,
        simulatedAt = simulatedAt,
        simulatorVersion = SimulatorVersion.V1,
        scenario = scenario,
        decision = decision,
        outcome = outcome,
    )
}
