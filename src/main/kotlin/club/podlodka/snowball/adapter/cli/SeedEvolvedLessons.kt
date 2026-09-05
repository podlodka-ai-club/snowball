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
import club.podlodka.snowball.domain.PromotionScenario
import club.podlodka.snowball.domain.PromotionScenarioEvent
import club.podlodka.snowball.domain.SimulatorVersion
import club.podlodka.snowball.port.ScenarioPublisher
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.reader

/**
 * The second half of the schema-evolution experiment: fill the dimension the memory asked for.
 *
 * The first half is a migration that adds `stock_cover` to `Lesson`. This writes the lessons that
 * use it - and only where the memory's own evidence justified the split. A bucket whose cases
 * disagree with its recommendation along stock cover gets two children, `tight` and `ample`, each
 * aggregated from its share of the parent's cases and linked to them. Every other bucket is left
 * exactly as it was: the parent stays as the fallback level, so nothing the cascade already
 * answers becomes worse.
 *
 * The condition itself is not read from the simulator. It is computed from two numbers the agent
 * sees in every scenario - stock and baseline sales - and the choice of this feature over the
 * others came from measuring which one best separated disagreeing evidence.
 */
object SeedEvolvedLessons {
    private const val MIN_CASES_TO_SPLIT = 6
    private const val DISAGREEMENT = 0.7
    private const val MIN_CHILD_CASES = 3
    private const val FIELD = "stock_cover"

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

        fun cover(s: PromotionScenario): String =
            if (s.stock.toDouble() / s.baselineSales.coerceAtLeast(1) < 2.0) "tight" else "ample"

        fun best(p: Map<Discount, BigDecimal>): Discount =
            Discount.entries
                .sortedWith(compareByDescending<Discount> { p.getValue(it) }.thenBy { it.percent })
                .first()

        val evidence =
            events.map { event ->
                val profits =
                    Discount.entries.associateWith { engine.simulate(event.scenarioId, event.scenario, it).grossProfit }
                Triple(
                    event.scenario,
                    CaseEvidence("CASE-${SimulatorVersion.V1.wire}-${event.scenarioId}", profits, best(profits)),
                    cover(event.scenario),
                )
            }

        val byBucket = mutableMapOf<LessonKey, MutableList<Pair<CaseEvidence, String>>>()
        evidence.forEach { (scenario, case, cov) ->
            LessonKey.bucketsFor(scenario).forEach { key -> byBucket.getOrPut(key) { mutableListOf() } += case to cov }
        }

        fun agreement(cases: List<CaseEvidence>): Double {
            val rec = Lesson.from(cases.first().let { byBucket.keys.first() }, cases).recommendedDiscount
            return cases.count { it.bestDiscount == rec }.toDouble() / cases.size
        }

        // Which buckets disagree with themselves, and does stock cover separate their cases.
        data class Split(
            val parent: LessonKey,
            val children: Map<String, List<CaseEvidence>>,
            val before: Double,
            val after: Double,
        )
        val splits =
            byBucket.mapNotNull { (key, cases) ->
                if (cases.size < MIN_CASES_TO_SPLIT) return@mapNotNull null
                val before = agreement(cases.map { it.first })
                if (before >= DISAGREEMENT) return@mapNotNull null
                val groups = cases.groupBy({ it.second }, { it.first })
                if (groups.size < 2 || groups.any { it.value.size < MIN_CHILD_CASES }) return@mapNotNull null
                val after = groups.values.sumOf { g -> agreement(g) * g.size } / cases.size
                if (after > before + 0.05) Split(key, groups, before, after) else null
            }

        println("buckets that disagree with themselves and separate by $FIELD: ${splits.size}")
        splits.forEach { split ->
            println(
                "  ${split.parent.wire}\n    agreement ${"%.2f".format(split.before)} -> " +
                    "${"%.2f".format(split.after)}; children: " +
                    split.children.entries.joinToString { (v, cs) -> "$v(${cs.size})" },
            )
        }
        if (dryRun) {
            println("\n--dry-run: nothing written")
            return
        }

        val instance =
            requireNotNull(System.getenv("XMEM_INSTANCE_ID_EVOLVING") ?: System.getenv("XMEM_INSTANCE_ID")) {
                "set XMEM_INSTANCE_ID_EVOLVING"
            }
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

        var lessons = 0
        var links = 0
        splits.forEach { split ->
            split.children.forEach { (value, cases) ->
                val keyWire = "${split.parent.wire}|$FIELD:$value"
                val lesson = Lesson.from(split.parent, cases)
                memory.saveLessonAs(lesson, keyWire, mapOf(FIELD to value))
                lessons += 1
                cases.forEach { case ->
                    memory.linkCaseToLessonKey(case.caseId, keyWire)
                    links += 1
                }
                println("  wrote $keyWire  (${cases.size} cases, recommends ${lesson.recommendedDiscount.percent}%)")
            }
        }
        println("\nevolved lessons written: $lessons, evidence links: $links")
    }
}
