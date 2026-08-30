package club.podlodka.snowball.contracts

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import java.time.OffsetDateTime

/**
 * The `event_type` envelope constant of `promotion-decision-v1.schema.json`.
 */
enum class DecisionEventType(
    @get:JsonValue val wire: String,
) {
    DECISION_CREATED("promotion.decision.created"),
}

/**
 * `promotion-decision-v1.schema.json`: the validated decision published by the Promotion Agent,
 * carrying the unchanged scenario snapshot it was taken from.
 */
data class PromotionDecisionEvent(
    @JsonProperty("event_type", required = true)
    val eventType: DecisionEventType,
    @JsonProperty("schema_version", required = true)
    val schemaVersion: SchemaVersion,
    @JsonProperty("decision_id", required = true)
    val decisionId: String,
    @JsonProperty("scenario_id", required = true)
    val scenarioId: String,
    @JsonProperty("decided_at", required = true)
    val decidedAt: OffsetDateTime,
    @JsonProperty("scenario", required = true)
    val scenario: PromotionScenario,
    @JsonProperty("decision", required = true)
    val decision: PromotionDecision,
) {
    constructor(
        decisionId: String,
        scenarioId: String,
        decidedAt: OffsetDateTime,
        scenario: PromotionScenario,
        decision: PromotionDecision,
    ) : this(
        eventType = DecisionEventType.DECISION_CREATED,
        schemaVersion = SchemaVersion.V1,
        decisionId = decisionId,
        scenarioId = scenarioId,
        decidedAt = decidedAt,
        scenario = scenario,
        decision = decision,
    )
}
