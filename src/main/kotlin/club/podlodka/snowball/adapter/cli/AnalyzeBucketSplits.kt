package club.podlodka.snowball.adapter.cli

import club.podlodka.snowball.adapter.context.DeterministicContextEnricher
import club.podlodka.snowball.adapter.simulator.SimulationEngine
import club.podlodka.snowball.adapter.source.DatasetBaselineSource
import club.podlodka.snowball.application.ScenarioGenerationService
import club.podlodka.snowball.domain.DatasetSplit
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.LessonKey
import club.podlodka.snowball.domain.PromotionScenario
import club.podlodka.snowball.domain.PromotionScenarioEvent
import club.podlodka.snowball.port.ScenarioPublisher
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import kotlin.io.path.reader

/**
 * Lets the memory find the conditions its own key is missing.
 *
 * The key was designed by hand: product, day type, weather, stock level. Anything the market cares
 * about but the key does not name ends up mixed inside one bucket, and the lesson averages over two
 * different situations. Adding a condition by hand does not work either - putting the event into
 * the key was measured and made things worse, because splitting every bucket costs more evidence
 * than the separation is worth.
 *
 * So the split is decided per bucket, from the evidence. A lesson already knows how much its cases
 * agree with it: the share whose own best action matches the recommendation. Where that share is
 * low the bucket is holding cases that want different things, and the question becomes which
 * observable feature separates them. Candidate features are ones the agent can see in a scenario -
 * never anything read out of the simulator.
 *
 * This is what A-MEM-style "memory that reorganises itself" looks like with a target function:
 * links are not redrawn because records look similar, but because the evidence behind a lesson
 * disagrees, and the result is checked in money.
 */
object AnalyzeBucketSplits {
    private const val VALIDATION = 50

    /** A bucket is only worth splitting if it has enough cases for the children to mean anything. */
    private const val MIN_CASES_TO_SPLIT = 8

    /** Below this level of agreement the bucket is treated as holding cases that want different things. */
    private const val DISAGREEMENT = 0.7

    /** A child with fewer cases than this is noise wearing a key. */
    private const val MIN_CHILD_CASES = 4

    @JvmStatic
    fun main(args: Array<String>) {
        val fixture = Path.of(args.firstOrNull() ?: "src/test/resources/fixtures/baseline.csv")
        val engine = SimulationEngine()

        val events = mutableListOf<PromotionScenarioEvent>()
        ScenarioGenerationService(
            baselineSource = DatasetBaselineSource { fixture.reader() },
            contextEnricher = DeterministicContextEnricher(),
            publisher = ScenarioPublisher { events += it },
        ).generate(DatasetSplit.TRAINING)

        fun profits(event: PromotionScenarioEvent): Map<Discount, BigDecimal> =
            Discount.entries.associateWith { engine.simulate(event.scenarioId, event.scenario, it).grossProfit }

        val all = events.map { it.scenario to profits(it) }
        val fit = all.dropLast(VALIDATION)
        val validation = all.takeLast(VALIDATION)

        fun best(byDiscount: Map<Discount, BigDecimal>): Discount =
            Discount.entries
                .sortedWith(compareByDescending<Discount> { byDiscount.getValue(it) }.thenBy { it.percent })
                .first()

        // Features the agent can observe in a scenario, none of them read from the simulator.
        val medianPrice =
            fit
                .groupBy { it.first.skuId }
                .mapValues { (_, rows) -> rows.map { it.first.price }.sorted()[rows.size / 2] }
        val features: Map<String, (PromotionScenario) -> String> =
            mapOf(
                "event" to { s -> s.eventType.wire },
                "price" to { s ->
                    val median = medianPrice[s.skuId] ?: s.price
                    if (s.price < median) {
                        "below"
                    } else if (s.price > median) {
                        "above"
                    } else {
                        "at"
                    }
                },
                "cover" to { s ->
                    val cover = s.stock.toDouble() / s.baselineSales.coerceAtLeast(1)
                    if (cover < 2.0) "tight" else "ample"
                },
                "temp" to { s -> if ((s.temperatureC?.toInt() ?: 0) >= 25) "warm" else "cool" },
            )

        fun recommend(cases: List<Map<Discount, BigDecimal>>): Discount =
            best(
                Discount.entries.associateWith { d ->
                    cases.fold(BigDecimal.ZERO) { sum, p -> sum.add(p.getValue(d)) }
                },
            )

        fun agreement(cases: List<Map<Discount, BigDecimal>>): Double {
            val rec = recommend(cases)
            return cases.count { best(it) == rec }.toDouble() / cases.size
        }

        val byBucket =
            fit
                .flatMap { (scenario, p) -> LessonKey.bucketsFor(scenario).map { it.wire to (scenario to p) } }
                .groupBy({ it.first }, { it.second })

        // For each disagreeing bucket, the feature that best separates its cases into children that
        // agree with themselves. Weighted by child size so a lucky split of two cases cannot win.
        val splits = mutableMapOf<String, String>()
        var considered = 0
        byBucket.forEach { (bucket, cases) ->
            if (cases.size < MIN_CASES_TO_SPLIT) return@forEach
            val before = agreement(cases.map { it.second })
            if (before >= DISAGREEMENT) return@forEach
            considered += 1
            val candidate =
                features
                    .mapNotNull { (name, feature) ->
                        val groups = cases.groupBy { feature(it.first) }
                        if (groups.size < 2 || groups.any { it.value.size < MIN_CHILD_CASES }) {
                            null
                        } else {
                            val after =
                                groups.values.sumOf { g -> agreement(g.map { it.second }) * g.size } / cases.size
                            name to after
                        }
                    }.maxByOrNull { it.second }
            if (candidate != null && candidate.second > before + 0.05) {
                splits[bucket] = candidate.first
            }
        }

        fun tableFor(cases: List<Pair<PromotionScenario, Map<Discount, BigDecimal>>>): Map<String, Discount> {
            val grouped =
                cases
                    .flatMap { (scenario, p) ->
                        LessonKey.bucketsFor(scenario).flatMap { key ->
                            val bucket = key.wire
                            val child = splits[bucket]?.let { "$bucket|$it:${features.getValue(it)(scenario)}" }
                            listOfNotNull(bucket to p, child?.let { it to p })
                        }
                    }.groupBy({ it.first }, { it.second })
            return grouped.mapValues { (_, group) -> recommend(group) }
        }

        fun score(
            label: String,
            table: Map<String, Discount>,
            useSplits: Boolean,
        ) {
            val advised =
                validation.map { (scenario, p) ->
                    val keys =
                        LessonKey.bucketsFor(scenario).flatMap { key ->
                            val bucket = key.wire
                            val child =
                                if (useSplits) {
                                    splits[bucket]?.let { "$bucket|$it:${features.getValue(it)(scenario)}" }
                                } else {
                                    null
                                }
                            listOfNotNull(child, bucket)
                        }
                    keys.firstNotNullOfOrNull { table[it] } to p
                }
            val covered = advised.filter { it.first != null }
            val optimal = covered.count { (advice, p) -> advice == best(p) }
            val loss =
                advised.fold(BigDecimal.ZERO) { sum, (advice, p) ->
                    sum.add(p.getValue(best(p)).subtract(p.getValue(advice ?: Discount.NONE)))
                }
            println(
                "%-32s %6d/%-3d %7s%%  %11s".format(
                    label,
                    covered.size,
                    validation.size,
                    if (covered.isEmpty()) "-" else percent(optimal, covered.size),
                    loss.setScale(2, RoundingMode.HALF_UP),
                ),
            )
        }

        println("Learned from the first ${fit.size} training scenarios, scored on the last $VALIDATION.\n")
        println("policy                           coverage  optimal        loss")
        val table = tableFor(fit)
        score("cascade as built", table, useSplits = false)
        score("cascade + evidence-driven splits", table, useSplits = true)

        println("\nBuckets that disagreed with themselves: $considered; split: ${splits.size}")
        splits.entries
            .groupBy { it.value }
            .toList()
            .sortedByDescending { it.second.size }
            .forEach { (feature, entries) -> println("  by $feature: ${entries.size}") }
        println("\nExamples of what the memory decided to split on:")
        splits.entries.take(6).forEach { (bucket, feature) -> println("  $bucket  ->  split by $feature") }
    }

    private fun percent(
        part: Int,
        whole: Int,
    ): String = BigDecimal(part * 100).divide(BigDecimal(whole), 1, RoundingMode.HALF_UP).toString()
}
