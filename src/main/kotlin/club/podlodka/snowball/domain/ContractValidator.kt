package club.podlodka.snowball.domain

import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion

/** An event that failed its committed contract and must not leave the component. */
class ContractViolation(
    message: String,
) : IllegalArgumentException(message)

/**
 * Validates outgoing events against the committed JSON Schemas.
 *
 * The typed models already refuse most invalid documents, but they are hand-written, and this is
 * the boundary where a divergence between a model and its contract would otherwise escape into
 * the rest of the system. The schemas are the ones under `docs/`, copied onto the classpath by the
 * build so there is exactly one source of truth.
 */
object ContractValidator {
    private const val SCENARIO_SCHEMA = "/contracts/scenario-generator/promotion-scenario-v1.schema.json"
    private const val OUTCOME_SCHEMA = "/contracts/market-simulator/promotion-outcome-v1.schema.json"
    private const val DECISION_SCHEMA = "/contracts/promotion-agent/promotion-decision-v1.schema.json"

    private val factory: JsonSchemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    private val config: SchemaValidatorsConfig =
        SchemaValidatorsConfig
            .builder()
            .formatAssertionsEnabled(true)
            .build()

    private val scenarioSchema: JsonSchema by lazy { load(SCENARIO_SCHEMA) }

    private val outcomeSchema: JsonSchema by lazy { load(OUTCOME_SCHEMA) }

    private val decisionSchema: JsonSchema by lazy { load(DECISION_SCHEMA) }

    fun validateScenario(event: PromotionScenarioEvent) {
        val document = ContractJson.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(event)
        val failures = scenarioSchema.validate(document).map { it.message }
        if (failures.isNotEmpty()) {
            throw ContractViolation(
                "scenario ${event.scenarioId} violates its contract: ${failures.joinToString("; ")}",
            )
        }
    }

    fun validateDecision(event: PromotionDecisionEvent) {
        val document = ContractJson.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(event)
        val failures = decisionSchema.validate(document).map { it.message }
        if (failures.isNotEmpty()) {
            throw ContractViolation(
                "decision ${event.decisionId} violates its contract: ${failures.joinToString("; ")}",
            )
        }
    }

    fun validateOutcome(event: PromotionOutcomeEvent) {
        val document = ContractJson.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(event)
        val failures = outcomeSchema.validate(document).map { it.message }
        if (failures.isNotEmpty()) {
            throw ContractViolation("outcome ${event.outcomeId} violates its contract: ${failures.joinToString("; ")}")
        }
    }

    private fun load(resource: String): JsonSchema {
        val url =
            ContractValidator::class.java.getResource(resource)
                ?: throw IllegalStateException(
                    "contract schema $resource is not on the classpath; check the build's schema copy step",
                )
        return factory.getSchema(SchemaLocation.of(url.toURI().toString()), config)
    }
}
