package club.podlodka.snowball.adapter.cli

import club.podlodka.snowball.adapter.memory.CachingEvidenceMemory
import club.podlodka.snowball.adapter.memory.InMemoryLearningMemory
import club.podlodka.snowball.adapter.memory.XmemoryHttp
import club.podlodka.snowball.adapter.memory.XmemoryLearningMemory
import club.podlodka.snowball.config.XmemoryConfig
import club.podlodka.snowball.domain.DatasetSplit
import club.podlodka.snowball.port.LearningMemory
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Runs the loop over a scenario set and prints what it cost and what it learned.
 *
 * Training and benchmarking are the same command with different flags, deliberately: two separate
 * entry points would be two chances for the arms to differ in something other than memory.
 */
object RunExperiment {
    private fun usage(): String =
        """
        Usage: run-experiment [options]

          --split <name>       training | benchmark | all   (default: training)
          --limit <n>          stop after n scenarios
          --memory <kind>      xmemory | memory            (default: xmemory)
          --arm <name>         clean | trained; reads XMEM_INSTANCE_ID_CLEAN / _TRAINED
          --instance <id>      xmemory instance; overrides --arm, defaults to XMEM_INSTANCE_ID
          --no-learning        evaluate without writing cases or lessons; also LEARNING_ENABLED=false
          --fixture <path>     baseline fixture CSV
          --model <id>         model name as the server reports it
          --model-url <url>    OpenAI-compatible base URL
        """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.contains("--help")) {
            println(usage())
            return
        }
        val options = args.toList()

        fun option(
            name: String,
            fallback: String? = null,
        ): String? {
            val index = options.indexOf("--$name")
            val inline = options.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')
            return inline ?: (if (index >= 0) options.getOrNull(index + 1) else null) ?: fallback
        }

        val split =
            when (val name = option("split", "training")) {
                "all" -> null
                else -> DatasetSplit.fromWire(name!!)
            }
        val limit = option("limit")?.toIntOrNull()
        // The flag wins over the variable, and the variable over the default: a benchmark arm is
        // configured once in the environment, while a one-off run says so on the command line.
        val learning =
            when {
                options.contains("--no-learning") -> false
                else -> System.getenv("LEARNING_ENABLED")?.lowercase() != "false"
            }
        val fixture = Path.of(option("fixture", "src/test/resources/fixtures/baseline.csv")!!)
        val modelId = option("model", System.getenv("DECISION_MODEL") ?: "muse-glimmer-30b-q3")!!
        val modelUrl = option("model-url", System.getenv("DECISION_MODEL_BASE_URL") ?: "http://192.168.1.212:8080/v1")!!

        val memory: LearningMemory =
            if (option("memory", "xmemory") == "memory") {
                InMemoryLearningMemory()
            } else {
                // Naming the arm rather than pasting an id: one mistyped identifier already cost a
                // benchmark run, and it failed as fifty ordinary-looking measurements rather than
                // as an error.
                val instance =
                    option("instance")
                        ?: when (option("arm")) {
                            "clean" -> System.getenv("XMEM_INSTANCE_ID_CLEAN")
                            "trained" -> System.getenv("XMEM_INSTANCE_ID_TRAINED")
                            null -> System.getenv("XMEM_INSTANCE_ID")
                            else -> error("unknown --arm ${option("arm")}; use clean or trained")
                        }
                requireNotNull(instance) { "pass --instance or --arm, or set XMEM_INSTANCE_ID" }
                val apiKey = requireNotNull(System.getenv("XMEM_API_KEY")) { "set XMEM_API_KEY" }
                val http =
                    XmemoryHttp(
                        XmemoryConfig(
                            baseUrl = System.getenv("XMEM_BASE_URL") ?: XmemoryConfig.DEFAULT_BASE_URL,
                            instanceId = instance,
                            apiKey = apiKey,
                            requestTimeout = Duration.ofSeconds(90),
                        ),
                    )
                // Checked before the first scenario: a run against a memory that is not there
                // measures nothing, and does so without ever looking broken.
                http.requireInstance()
                val xmemory = XmemoryLearningMemory(http)
                // Lessons are aggregated from evidence this same run mostly wrote; reading it back
                // per lesson would spend a shared model quota on facts already at hand.
                CachingEvidenceMemory(xmemory, xmemory::allEvidence)
            }

        val started = Instant.now()
        val summary =
            RunLoop(
                fixture = fixture,
                memory = memory,
                modelBaseUrl = modelUrl,
                modelId = modelId,
                learningEnabled = learning,
            ).run(split, limit)
        val elapsed = Duration.between(started, Instant.now())

        println("scenarios      ${summary.scenarios}")
        println("mean regret    ${summary.meanRegret}")
        println("optimal rate   ${summary.optimalRate}%")
        println("fallbacks      ${summary.fallbacks}")
        println("elapsed        ${elapsed.toSeconds()}s")
        println("per scenario   ${if (summary.scenarios > 0) elapsed.toMillis() / summary.scenarios else 0}ms")
        println("learning       ${if (learning) "on" else "off"}")
    }
}
