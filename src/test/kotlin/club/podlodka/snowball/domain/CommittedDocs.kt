package club.podlodka.snowball.domain

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Access to the committed contract material under `docs/`.
 *
 * Both the examples and the schemas are read here, and the schemas are compiled into real
 * validators. Reading only the examples is not enough: a test that hard-codes "snow is not a
 * weather" keeps passing after somebody adds `snow` to the committed enum, which leaves the model
 * and the contract silently disagreeing. Negative tests therefore assert against the schema and
 * the model together.
 */
object CommittedDocs {
    private val docsDir: Path =
        Path.of(
            requireNotNull(System.getProperty("snowball.docs.dir")) {
                "System property snowball.docs.dir is not set; check the test task configuration"
            },
        )

    const val SCENARIO_EXAMPLE: String = "scenario-generator/promotion-scenario-v1.example.json"
    const val DECISION_EXAMPLE: String = "promotion-agent/promotion-decision-v1.example.json"
    const val OUTCOME_EXAMPLE: String = "market-simulator/promotion-outcome-v1.example.json"

    const val SCENARIO_SCHEMA: String = "scenario-generator/promotion-scenario-v1.schema.json"
    const val DECISION_SCHEMA: String = "promotion-agent/promotion-decision-v1.schema.json"
    const val OUTCOME_SCHEMA: String = "market-simulator/promotion-outcome-v1.schema.json"

    private val schemaFactory: JsonSchemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    private val validatorsConfig: SchemaValidatorsConfig =
        SchemaValidatorsConfig
            .builder()
            .formatAssertionsEnabled(true)
            .build()

    fun read(relativePath: String): String = docsDir.resolve(relativePath).readText()

    /** The committed schema, compiled. Relative `$ref` resolve against the committed tree. */
    fun schema(relativePath: String): JsonSchema =
        schemaFactory.getSchema(
            SchemaLocation.of(docsDir.resolve(relativePath).toUri().toString()),
            validatorsConfig,
        )

    /** Schema validation messages for [document]; empty when the document conforms. */
    fun validate(
        schemaPath: String,
        document: JsonNode,
    ): List<String> = schema(schemaPath).validate(document).map { it.message }
}
