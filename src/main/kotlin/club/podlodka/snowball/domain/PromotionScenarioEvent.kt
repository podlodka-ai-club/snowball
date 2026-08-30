package club.podlodka.snowball.domain

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue
import java.time.OffsetDateTime

/**
 * The `event_type` envelope constant of `promotion-scenario-v1.schema.json`.
 */
enum class ScenarioEventType(
    @get:JsonValue val wire: String,
) {
    SCENARIO_CREATED("promotion.scenario.created"),
}

/**
 * `promotion-scenario-v1.schema.json`: the immutable scenario published by the Scenario Generator.
 *
 * The envelope constants are ordinary required properties rather than defaulted ones, because a
 * Kotlin default would let a document that omits them parse as v1 instead of failing. The secondary
 * constructor supplies them for code that builds an event rather than reading one.
 */
data class PromotionScenarioEvent(
    @JsonProperty("event_type", required = true)
    val eventType: ScenarioEventType,
    @JsonProperty("schema_version", required = true)
    val schemaVersion: SchemaVersion,
    @JsonProperty("scenario_id", required = true)
    val scenarioId: String,
    @JsonProperty("generated_at", required = true)
    val generatedAt: OffsetDateTime,
    @JsonProperty("source", required = true)
    val source: ScenarioSource,
    @JsonProperty("scenario", required = true)
    val scenario: PromotionScenario,
) {
    constructor(
        scenarioId: String,
        generatedAt: OffsetDateTime,
        source: ScenarioSource,
        scenario: PromotionScenario,
    ) : this(
        eventType = ScenarioEventType.SCENARIO_CREATED,
        schemaVersion = SchemaVersion.V1,
        scenarioId = scenarioId,
        generatedAt = generatedAt,
        source = source,
        scenario = scenario,
    )
}
