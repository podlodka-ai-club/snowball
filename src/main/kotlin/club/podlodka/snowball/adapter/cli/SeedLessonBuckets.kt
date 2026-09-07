package club.podlodka.snowball.adapter.cli

import club.podlodka.snowball.adapter.context.DeterministicContextEnricher
import club.podlodka.snowball.adapter.memory.XmemoryHttp
import club.podlodka.snowball.adapter.memory.XmemoryLearningMemory
import club.podlodka.snowball.adapter.simulator.SimulationEngine
import club.podlodka.snowball.adapter.source.DatasetBaselineSource
import club.podlodka.snowball.application.ScenarioGenerationService
import club.podlodka.snowball.config.XmemoryConfig
import club.podlodka.snowball.domain.CaseEvidence
import club.podlodka.snowball.domain.DatasetSplit
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.Lesson
import club.podlodka.snowball.domain.LessonKey
import club.podlodka.snowball.domain.PromotionCase
import club.podlodka.snowball.domain.PromotionScenarioEvent
import club.podlodka.snowball.domain.SimulatorVersion
import club.podlodka.snowball.port.ScenarioPublisher
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.reader

/**
 * Writes the lesson buckets a past training run never created.
 *
 * The run that filled this memory only knew one bucket per scope. The cascade needs the looser
 * ones too, and re-running the whole training to get them would cost an hour of model calls to
 * rediscover facts that are already stored - the evidence behind a lesson is the simulator's
 * verdict on four actions, and the simulator is deterministic, so every case can be recomputed
 * here exactly as it was, without asking the model anything.
 *
 * Only lessons and their evidence links are written; the cases themselves are already there. Any
 * bucket that already exists is rewritten with the same aggregate, so running this twice changes
 * nothing.
 */
object SeedLessonBuckets {
    @JvmStatic
    fun main(args: Array<String>) {
        val fixture =
            Path.of(
                args.firstOrNull()?.takeUnless { it.startsWith("--") } ?: "src/test/resources/fixtures/baseline.csv",
            )
        val instance =
            requireNotNull(System.getenv("XMEM_INSTANCE_ID_TRAINED") ?: System.getenv("XMEM_INSTANCE_ID")) {
                "set XMEM_INSTANCE_ID_TRAINED"
            }
        val apiKey = requireNotNull(System.getenv("XMEM_API_KEY")) { "set XMEM_API_KEY" }

        val engine = SimulationEngine()
        val events = mutableListOf<PromotionScenarioEvent>()
        ScenarioGenerationService(
            baselineSource = DatasetBaselineSource { fixture.reader() },
            contextEnricher = DeterministicContextEnricher(),
            publisher = ScenarioPublisher { events += it },
        ).generate(DatasetSplit.TRAINING)

        // The same case identity the evaluator writes, so the links land on the stored cases.
        val evidence =
            events.associate { event ->
                val profits =
                    Discount.entries.associateWith { engine.simulate(event.scenarioId, event.scenario, it).grossProfit }
                val best =
                    Discount.entries
                        .sortedWith(compareByDescending<Discount> { profits.getValue(it) }.thenBy { it.percent })
                        .first()
                event to CaseEvidence("CASE-${SimulatorVersion.V1.wire}-${event.scenarioId}", profits, best)
            }

        val byBucket = mutableMapOf<LessonKey, MutableList<CaseEvidence>>()
        evidence.forEach { (event, case) ->
            LessonKey.bucketsFor(event.scenario).forEach { key -> byBucket.getOrPut(key) { mutableListOf() } += case }
        }
        val lessons = byBucket.map { (key, cases) -> Lesson.from(key, cases) }
        val links = byBucket.flatMap { (key, cases) -> cases.map { it.caseId to key } }

        // Rewriting a lesson resets its visibility, so a resumed run can write the links alone.
        val stage = args.firstOrNull { it.startsWith("--") && !it.startsWith("--decisions=") } ?: "all"
        val doCases = stage == "--cases-only"
        val doLessons = stage == "all" || stage == "--lessons-only"
        val doLinks = stage == "all" || stage == "--links-only"

        // Cases are only written into an empty instance, and never invented: the chosen action
        // comes from the log of the real training run, so a seeded case is the case that run
        // produced, not a fiction with the oracle's answer written into the "chosen" column.
        val decisionsLog = args.firstOrNull { it.startsWith("--decisions=") }?.removePrefix("--decisions=")
        val chosenByScenario: Map<String, Discount> =
            decisionsLog
                ?.let { path ->
                    val pattern = Regex("""evaluated scenario_id=(\S+) chosen=(\d+)""")
                    Path
                        .of(path)
                        .toFile()
                        .readLines()
                        .mapNotNull { line ->
                            pattern.find(line)?.let { m ->
                                m.groupValues[1] to Discount.fromPercent(m.groupValues[2].toInt())
                            }
                        }.toMap()
                }.orEmpty()
        require(!doCases || chosenByScenario.isNotEmpty()) { "--cases-only needs --decisions=<training log>" }

        println("stage         $stage")
        println("scenarios     ${events.size}")
        println("buckets       ${lessons.size}")
        println("links         ${links.size}")

        val memory =
            XmemoryLearningMemory(
                XmemoryHttp(
                    XmemoryConfig(
                        baseUrl = System.getenv("XMEM_BASE_URL") ?: XmemoryConfig.DEFAULT_BASE_URL,
                        instanceId = instance,
                        apiKey = apiKey,
                        requestTimeout = Duration.ofSeconds(120),
                    ),
                ).also { it.requireInstance() },
            )

        val started = System.currentTimeMillis()
        if (doCases) {
            var written = 0
            events.forEach { event ->
                val chosen = chosenByScenario[event.scenarioId] ?: return@forEach
                val outcome = engine.simulate(event.scenarioId, event.scenario, chosen)
                val case = evidence.getValue(event)
                memory.saveCase(
                    PromotionCase(
                        caseId = case.caseId,
                        scenarioId = event.scenarioId,
                        simulatorVersion = SimulatorVersion.V1,
                        scenario = event.scenario,
                        chosenDiscount = chosen,
                        chosenUnitsSold = outcome.unitsSold,
                        chosenGrossProfit = outcome.grossProfit,
                        profitByDiscount = case.profitByDiscount,
                        bestDiscount = case.bestDiscount,
                    ),
                )
                written += 1
                if (written % 50 == 0) println("cases $written/${events.size}")
            }
            println("cases $written/${events.size} (${events.size - written} had no recorded decision)")
        }
        memory.seed(
            if (doLessons) lessons else emptyList(),
            if (doLinks) links else emptyList(),
        ) { println(it) }
        println("elapsed       ${(System.currentTimeMillis() - started) / 1000}s")
    }
}
