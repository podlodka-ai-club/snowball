package club.podlodka.snowball.adapter.cli

import club.podlodka.snowball.adapter.context.DeterministicContextEnricher
import club.podlodka.snowball.adapter.memory.XmemoryHttp
import club.podlodka.snowball.adapter.memory.XmemoryLearningMemory
import club.podlodka.snowball.adapter.simulator.SimulationEngine
import club.podlodka.snowball.adapter.source.DatasetBaselineSource
import club.podlodka.snowball.application.ScenarioGenerationService
import club.podlodka.snowball.config.XmemoryConfig
import club.podlodka.snowball.domain.DatasetSplit
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.Lesson
import club.podlodka.snowball.domain.LessonKey
import club.podlodka.snowball.domain.PromotionScenarioEvent
import club.podlodka.snowball.port.ScenarioPublisher
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.reader

/**
 * Writes the lessons a blind learner arrives at, so a live benchmark arm can read them.
 *
 * Trained on all 250 training scenarios, one observed profit each, oracle never consulted. The
 * lessons land under the same keys the oracle learner uses, so the agent's recall path is
 * identical - the only difference between the arms is what the lessons say and on what evidence.
 */
object SeedBlindLessons {
    @JvmStatic
    fun main(args: Array<String>) {
        val fixture = Path.of(args.firstOrNull { !it.startsWith("--") } ?: "src/test/resources/fixtures/baseline.csv")
        val dryRun = args.contains("--dry-run")
        val engine = SimulationEngine()

        val events = mutableListOf<PromotionScenarioEvent>()
        ScenarioGenerationService(
            baselineSource = DatasetBaselineSource { fixture.reader() },
            contextEnricher = DeterministicContextEnricher(),
            publisher = ScenarioPublisher { events += it },
        ).generate(DatasetSplit.TRAINING)
        val cases =
            events.map { event ->
                event to
                    Discount.entries.associateWith { engine.simulate(event.scenarioId, event.scenario, it).grossProfit }
            }

        val (buckets, exploration) = BlindLearner.learn(cases)
        val keysByWire = events.flatMap { LessonKey.bucketsFor(it.scenario) }.associateBy { it.wire }

        val lessons =
            buckets.mapNotNull { (wire, bucket) ->
                val key = keysByWire[wire] ?: return@mapNotNull null
                val ranked = bucket.meanByAction.entries.sortedByDescending { it.value }
                val best = ranked.first()
                val runnerUp = ranked.getOrNull(1)?.value ?: BigDecimal.ZERO
                val advantage =
                    if (runnerUp.signum() == 0) {
                        BigDecimal.ZERO
                    } else {
                        best.value
                            .subtract(runnerUp)
                            .divide(runnerUp.abs(), 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal(100))
                            .setScale(2, RoundingMode.HALF_UP)
                    }
                val tried = bucket.meanByAction.size
                val confidence =
                    BigDecimal(
                        0.6 * minOf(bucket.visits / 5.0, 1.0) + 0.25 * (tried / 4.0) +
                            0.15 * (advantage.toDouble() / 10.0).coerceIn(0.0, 1.0),
                    ).setScale(2, RoundingMode.HALF_UP)
                Lesson(
                    key = key,
                    recommendedDiscount = best.key,
                    evidenceCount = bucket.visits,
                    avgProfitAdvantagePct = advantage,
                    confidence = confidence,
                    rationale =
                        "For ${key.scope.prefix}:${key.scopeValue}, ${best.key.percent}% has the highest mean gross " +
                            "profit across ${bucket.visits} observed cases ($tried of 4 actions ever tried, only the " +
                            "chosen action observed), beating the next-best tried action by $advantage%.",
                )
            }

        println("training scenarios  ${events.size}")
        println("blind buckets       ${lessons.size} (of ${keysByWire.size} the oracle learner fills)")
        println("exploration cost    ${exploration.setScale(2, RoundingMode.HALF_UP)}")
        if (dryRun) {
            println("--dry-run: nothing written")
            return
        }

        val instance = requireNotNull(System.getenv("XMEM_INSTANCE_ID_BLIND")) { "set XMEM_INSTANCE_ID_BLIND" }
        val memory =
            XmemoryLearningMemory(
                XmemoryHttp(
                    XmemoryConfig(
                        baseUrl = System.getenv("XMEM_BASE_URL") ?: XmemoryConfig.DEFAULT_BASE_URL,
                        instanceId = instance,
                        apiKey = requireNotNull(System.getenv("XMEM_API_KEY")) { "set XMEM_API_KEY" },
                        requestTimeout = Duration.ofSeconds(120),
                    ),
                ).also { it.requireInstance() },
            )
        val started = System.currentTimeMillis()
        memory.seed(lessons, emptyList()) { println(it) }
        println("elapsed             ${(System.currentTimeMillis() - started) / 1000}s")
    }
}
