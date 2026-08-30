package club.podlodka.snowball.contracts

import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Access to the committed contract material under `docs/`.
 *
 * The tests read the originals rather than a copy, so a change to a schema or example is noticed
 * here instead of at the first integration.
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

    fun read(relativePath: String): String = docsDir.resolve(relativePath).readText()
}
