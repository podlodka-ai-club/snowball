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
        val stage = args.getOrNull(1) ?: args.firstOrNull()?.takeIf { it.startsWith("--") } ?: "all"
        val doLessons = stage != "--links-only"
        val doLinks = stage != "--lessons-only"

        println("stage         ${if (doLessons && doLinks) "lessons and links" else stage}")
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
        memory.seed(
            if (doLessons) lessons else emptyList(),
            if (doLinks) links else emptyList(),
        ) { println(it) }
        println("elapsed       ${(System.currentTimeMillis() - started) / 1000}s")
    }
}
