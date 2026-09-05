package club.podlodka.snowball.adapter.cli

import club.podlodka.snowball.adapter.context.DeterministicContextEnricher
import club.podlodka.snowball.adapter.source.DatasetBaselineSource
import club.podlodka.snowball.application.ScenarioGenerationService
import club.podlodka.snowball.application.ScenarioGenerationTrigger
import club.podlodka.snowball.domain.ContractJson
import club.podlodka.snowball.domain.DatasetSplit
import club.podlodka.snowball.domain.PromotionScenarioEvent
import club.podlodka.snowball.port.ScenarioPublisher
import java.io.PrintStream
import java.nio.file.Path
import kotlin.io.path.reader

/** How the run was asked for, once the arguments have been understood. */
data class CliOptions(
    val fixture: Path,
    val split: DatasetSplit?,
    val limit: Int?,
    val pretty: Boolean,
) {
    companion object {
        const val USAGE: String =
            """
            Usage: generate-scenarios [options]

              --fixture <path>   normalized baseline fixture CSV
                                 (default: src/test/resources/fixtures/baseline.csv)
              --split <name>     training | benchmark | all   (default: all)
              --limit <n>        stop after n scenarios
              --pretty           indent each scenario instead of one per line
              --help             print this and exit

            Scenarios go to stdout, one JSON document per line unless --pretty.
            The run report goes to stderr, so stdout stays pipeable.
            """

        fun parse(args: Array<String>): CliOptions {
            var fixture = Path.of("src/test/resources/fixtures/baseline.csv")
            var split: DatasetSplit? = null
            var limit: Int? = null
            var pretty = false
            // Both `--split benchmark` and `--split=benchmark` are accepted: the second is the
            // form people actually type, and refusing it would be a papercut with no upside.
            var index = 0
            while (index < args.size) {
                val raw = args[index]
                val inlined = if (raw.contains('=')) raw.substringAfter('=') else null
                when (val argument = raw.substringBefore('=')) {
                    "--fixture" -> {
                        fixture = Path.of(inlined ?: value(args, ++index, argument))
                    }

                    "--split" -> {
                        split =
                            when (val name = inlined ?: value(args, ++index, argument)) {
                                "all" -> null
                                else -> DatasetSplit.fromWire(name)
                            }
                    }

                    "--limit" -> {
                        limit =
                            (inlined ?: value(args, ++index, argument)).toIntOrNull()?.takeIf { it > 0 }
                                ?: throw IllegalArgumentException("--limit expects a positive number")
                    }

                    "--pretty" -> {
                        pretty = true
                    }

                    else -> {
                        throw IllegalArgumentException("unknown argument: $raw")
                    }
                }
                index += 1
            }
            return CliOptions(fixture, split, limit, pretty)
        }

        private fun value(
            args: Array<String>,
            index: Int,
            argument: String,
        ): String = args.getOrNull(index) ?: throw IllegalArgumentException("$argument expects a value")
    }
}

/**
 * The manual trigger the specification asks for, as something a person can actually run.
 *
 * It exists so the stage can be looked at rather than only tested - printing what the generator
 * produces is the difference between "93 tests pass" and seeing a scenario. It is deliberately a
 * thin adapter: no generation logic lives here, and when the run orchestrator arrives it calls the
 * same service this does.
 */
fun runCli(
    args: Array<String>,
    stdout: PrintStream,
    stderr: PrintStream,
): Int {
    if (args.contains("--help")) {
        stderr.println(CliOptions.USAGE.trimIndent())
        return 0
    }
    val options =
        try {
            CliOptions.parse(args)
        } catch (failure: IllegalArgumentException) {
            stderr.println("${failure.message}\n")
            stderr.println(CliOptions.USAGE.trimIndent())
            return 2
        }

    var printed = 0
    val writer = ContractJson.mapper.let { if (options.pretty) it.writerWithDefaultPrettyPrinter() else it.writer() }
    val publisher =
        ScenarioPublisher { event: PromotionScenarioEvent ->
            if (options.limit == null || printed < options.limit) {
                stdout.println(writer.writeValueAsString(event))
                printed += 1
            }
        }

    return try {
        val service =
            ScenarioGenerationService(
                baselineSource = DatasetBaselineSource { options.fixture.reader() },
                contextEnricher = DeterministicContextEnricher(),
                publisher = publisher,
            )
        val report = ScenarioGenerationTrigger(service).runNow(options.split)
        stderr.println("generated=${report.published} printed=$printed rejected=${report.rejected.size}")
        report.rejected.forEach { stderr.println("  $it") }
        if (report.hasRejections) 1 else 0
    } catch (failure: Exception) {
        stderr.println("generation failed: ${failure.message}")
        1
    }
}

fun main(args: Array<String>) {
    kotlin.system.exitProcess(runCli(args, System.out, System.err))
}
