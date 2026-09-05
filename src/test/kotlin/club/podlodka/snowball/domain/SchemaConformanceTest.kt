package club.podlodka.snowball.domain

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Binds the models to the committed schemas rather than to a reviewer's memory of them.
 *
 * A negative test that only asserts "the model rejects this" silently stops meaning anything the
 * moment the schema changes to allow it. Every case here asserts both directions at once, so a
 * committed contract and its model cannot drift apart without a red test.
 */
class SchemaConformanceTest {
    private val mapper = ContractJson.mapper

    private data class Contract(
        val name: String,
        val schemaPath: String,
        val examplePath: String,
        val parse: (String) -> Any,
    )

    private val contracts =
        listOf(
            Contract("scenario", CommittedDocs.SCENARIO_SCHEMA, CommittedDocs.SCENARIO_EXAMPLE) {
                mapper.readValue<PromotionScenarioEvent>(it)
            },
            Contract("decision", CommittedDocs.DECISION_SCHEMA, CommittedDocs.DECISION_EXAMPLE) {
                mapper.readValue<PromotionDecisionEvent>(it)
            },
            Contract("outcome", CommittedDocs.OUTCOME_SCHEMA, CommittedDocs.OUTCOME_EXAMPLE) {
                mapper.readValue<PromotionOutcomeEvent>(it)
            },
        )

    @TestFactory
    fun `each committed example conforms to its committed schema`(): List<DynamicTest> =
        contracts.map { contract ->
            DynamicTest.dynamicTest(contract.name) {
                val document = mapper.readTree(CommittedDocs.read(contract.examplePath))

                assertThat(CommittedDocs.validate(contract.schemaPath, document)).isEmpty()
            }
        }

    @TestFactory
    fun `what a model serializes conforms to its committed schema`(): List<DynamicTest> =
        contracts.map { contract ->
            DynamicTest.dynamicTest(contract.name) {
                val model = contract.parse(CommittedDocs.read(contract.examplePath))
                val serialized = mapper.readTree(mapper.writeValueAsString(model))

                assertThat(CommittedDocs.validate(contract.schemaPath, serialized)).isEmpty()
            }
        }

    @TestFactory
    fun `the schema and the model reject the same documents`(): List<DynamicTest> {
        val cases =
            listOf(
                mutation("weather outside the enumeration", CommittedDocs.SCENARIO_SCHEMA, scenario()) {
                    (it.get("scenario") as ObjectNode).put("weather", "snow")
                },
                mutation("stock_level outside the enumeration", CommittedDocs.SCENARIO_SCHEMA, scenario()) {
                    (it.get("scenario") as ObjectNode).put("stock_level", "low")
                },
                mutation("day_type outside the enumeration", CommittedDocs.SCENARIO_SCHEMA, scenario()) {
                    (it.get("scenario") as ObjectNode).put("day_type", "holiday")
                },
                mutation("scenario event_type outside the enumeration", CommittedDocs.SCENARIO_SCHEMA, scenario()) {
                    (it.get("scenario") as ObjectNode).put("event_type", "festival")
                },
                mutation("discount outside the allowed actions", CommittedDocs.DECISION_SCHEMA, decision()) {
                    (it.get("decision") as ObjectNode).put("discount", 15)
                },
                mutation("unknown property", CommittedDocs.SCENARIO_SCHEMA, scenario()) {
                    it.put("hidden_noise_factor", 1.0)
                },
                mutation("missing required field", CommittedDocs.SCENARIO_SCHEMA, scenario()) {
                    it.remove("scenario_id")
                },
                mutation("empty minLength string", CommittedDocs.SCENARIO_SCHEMA, scenario()) {
                    it.put("scenario_id", "")
                },
                mutation("price at the exclusive minimum", CommittedDocs.SCENARIO_SCHEMA, scenario()) {
                    (it.get("scenario") as ObjectNode).put("price", 0.0)
                },
                mutation("negative stock", CommittedDocs.SCENARIO_SCHEMA, scenario()) {
                    (it.get("scenario") as ObjectNode).put("stock", -1)
                },
                mutation("negative units_sold", CommittedDocs.OUTCOME_SCHEMA, outcome()) {
                    (it.get("outcome") as ObjectNode).put("units_sold", -1)
                },
                mutation("wrong envelope constant", CommittedDocs.SCENARIO_SCHEMA, scenario()) {
                    it.put("schema_version", 2)
                },
                mutation("number in a string field", CommittedDocs.SCENARIO_SCHEMA, scenario()) {
                    (it.get("scenario") as ObjectNode).put("store_id", 12)
                },
                mutation("enumeration given as a number", CommittedDocs.SCENARIO_SCHEMA, scenario()) {
                    (it.get("scenario") as ObjectNode).put("stock_level", 1)
                },
                mutation("timestamp given as a number", CommittedDocs.SCENARIO_SCHEMA, scenario()) {
                    it.put("generated_at", 0)
                },
                mutation("null in an optional field", CommittedDocs.SCENARIO_SCHEMA, scenario()) {
                    (it.get("scenario") as ObjectNode).putNull("store_name")
                },
                mutation("null in a required numeric field", CommittedDocs.SCENARIO_SCHEMA, scenario()) {
                    (it.get("scenario") as ObjectNode).putNull("stock")
                },
            )

        return cases.map { case ->
            DynamicTest.dynamicTest(case.name) {
                val schemaErrors = CommittedDocs.validate(case.schemaPath, case.document)
                assertThat(schemaErrors)
                    .describedAs("the committed schema should reject: %s", case.name)
                    .isNotEmpty()

                val parseFailure = catchThrowable { parseAs(case.schemaPath, case.document) }
                assertThat(parseFailure)
                    .describedAs("the model should reject what the schema rejects: %s", case.name)
                    .isInstanceOf(JacksonException::class.java)
            }
        }
    }

    @Test
    fun `an unmutated example is accepted by both`() {
        val document = mapper.readTree(CommittedDocs.read(CommittedDocs.SCENARIO_EXAMPLE))

        assertThat(CommittedDocs.validate(CommittedDocs.SCENARIO_SCHEMA, document)).isEmpty()
        assertThat(catchThrowable { parseAs(CommittedDocs.SCENARIO_SCHEMA, document) }).isNull()
    }

    private data class Mutation(
        val name: String,
        val schemaPath: String,
        val document: ObjectNode,
    )

    private fun mutation(
        name: String,
        schemaPath: String,
        document: ObjectNode,
        mutate: (ObjectNode) -> Unit,
    ): Mutation {
        mutate(document)
        return Mutation(name, schemaPath, document)
    }

    private fun scenario(): ObjectNode =
        mapper.readTree(CommittedDocs.read(CommittedDocs.SCENARIO_EXAMPLE)) as ObjectNode

    private fun decision(): ObjectNode =
        mapper.readTree(CommittedDocs.read(CommittedDocs.DECISION_EXAMPLE)) as ObjectNode

    private fun outcome(): ObjectNode = mapper.readTree(CommittedDocs.read(CommittedDocs.OUTCOME_EXAMPLE)) as ObjectNode

    private fun parseAs(
        schemaPath: String,
        document: JsonNode,
    ): Any {
        val json = mapper.writeValueAsString(document)
        return when (schemaPath) {
            CommittedDocs.SCENARIO_SCHEMA -> mapper.readValue<PromotionScenarioEvent>(json)
            CommittedDocs.DECISION_SCHEMA -> mapper.readValue<PromotionDecisionEvent>(json)
            CommittedDocs.OUTCOME_SCHEMA -> mapper.readValue<PromotionOutcomeEvent>(json)
            else -> error("unknown schema $schemaPath")
        }
    }
}
