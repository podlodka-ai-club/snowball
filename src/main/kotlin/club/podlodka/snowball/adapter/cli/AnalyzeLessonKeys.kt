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
 * What a looser lesson key would buy, measured without spending anything.
 *
 * The benchmark showed the ceiling is coverage, not lesson quality: on the six scenarios whose key
 * had never been seen, the trained agent was as blind as the untrained one, and those six carried
 * most of the remaining loss. The obvious answer is to fall back to a more general key - but
 * generalising trades precision for reach, and which way that trade goes is an empirical question.
 *
 * It is answerable offline. The simulator is deterministic, so every scenario's four outcomes can
 * be recomputed here: the training cases without asking the model, and the held-out optimum without
 * asking anything. That makes this an upper bound on what the memory can offer - it measures the
 * advice, assuming an agent that follows it - and it costs no model tokens and no memory quota.
 */
object AnalyzeLessonKeys {
    @JvmStatic
    fun main(args: Array<String>) {
        val fixture = Path.of(args.firstOrNull() ?: "src/test/resources/fixtures/baseline.csv")
        val engine = SimulationEngine()

        fun scenarios(split: DatasetSplit): List<PromotionScenarioEvent> {
            val collected = mutableListOf<PromotionScenarioEvent>()
            ScenarioGenerationService(
                baselineSource = DatasetBaselineSource { fixture.reader() },
                contextEnricher = DeterministicContextEnricher(),
                publisher = ScenarioPublisher { collected += it },
            ).generate(split)
            return collected
        }

        /**
         * The four ground-truth profits, which is all a lesson ever aggregates.
         *
         * The scenario id has to be the real one: the market shock is derived from it, so reusing
         * a single id would give every scenario the same shock and quietly replace the oracle with
         * a fiction that still looks like numbers.
         */
        fun profits(event: PromotionScenarioEvent): Map<Discount, BigDecimal> =
            Discount.entries.associateWith { engine.simulate(event.scenarioId, event.scenario, it).grossProfit }

        // Two evaluations, and the first is the honest one. Choosing the key scheme on the
        // held-out set and then reporting the gain on that same set spends the held-out set on a
        // hyperparameter - so the scheme is checked first on a validation slice cut from the
        // training data (its last fifty scenarios, again split by time), where nothing was tuned.
        val allTraining = scenarios(DatasetSplit.TRAINING).map { it.scenario to profits(it) }
        val fit = allTraining.dropLast(VALIDATION)
        val validation = allTraining.takeLast(VALIDATION)
        val training = allTraining
        val benchmark = scenarios(DatasetSplit.BENCHMARK).map { it.scenario to profits(it) }

        // Ties prefer the lower discount, exactly as the learner does.
        fun best(byDiscount: Map<Discount, BigDecimal>): Discount =
            Discount.entries
                .sortedWith(compareByDescending<Discount> { byDiscount.getValue(it) }.thenBy { it.percent })
                .first()

        fun lessonsFrom(cases: List<Pair<PromotionScenario, Map<Discount, BigDecimal>>>): Map<String, Discount> =
            cases
                .flatMap { (scenario, profits) -> LessonKey.bucketsFor(scenario).map { it to profits } }
                .groupBy({ it.first.wire }, { it.second })
                .mapValues { (_, group) ->
                    best(
                        Discount.entries.associateWith { discount ->
                            group.fold(BigDecimal.ZERO) { sum, p -> sum.add(p.getValue(discount)) }
                        },
                    )
                }

        val lessons = lessonsFrom(training)

        /** What the agent would be told: strictest bucket per scope that has an answer. */
        fun advise(
            table: Map<String, Discount>,
            scenario: PromotionScenario,
            strictOnly: Boolean,
        ): Discount? =
            LessonKey
                .bucketsFor(scenario)
                .filter { !strictOnly || it.specificity == MOST_SPECIFIC }
                .groupBy { it.scope }
                .values
                .firstNotNullOfOrNull { buckets -> buckets.firstNotNullOfOrNull { table[it.wire] } }

        fun reportOn(
            cases: List<Pair<PromotionScenario, Map<Discount, BigDecimal>>>,
            table: Map<String, Discount>,
            label: String,
            strictOnly: Boolean,
        ) {
            val advised = cases.map { (s, p) -> advise(table, s, strictOnly) to p }
            val covered = advised.filter { it.first != null }
            val optimal = covered.count { (advice, p) -> advice == best(p) }
            // An uncovered scenario gets no advice, so its full regret against the oracle stands.
            val loss =
                advised.fold(BigDecimal.ZERO) { sum, (advice, p) ->
                    sum.add(p.getValue(best(p)).subtract(p.getValue(advice ?: Discount.NONE)))
                }
            println(
                "%-28s %6d/%-3d %7s%%  %11s".format(
                    label,
                    covered.size,
                    cases.size,
                    if (covered.isEmpty()) "-" else percent(optimal, covered.size),
                    loss.setScale(2, RoundingMode.HALF_UP),
                ),
            )
        }

        fun report(
            label: String,
            strictOnly: Boolean,
        ) = reportOn(benchmark, lessons, label, strictOnly)

        // The event is part of the market and not part of the key: the simulator gives a local
        // event its own demand and promo-affinity multipliers, different per category (beer
        // 1.20/1.25, yogurt 1.00/1.00), while every lesson key writes `event:any`. Bucketing two
        // regimes together has to cost something; this measures how much, before the key changes.
        fun eventAwareBuckets(scenario: PromotionScenario): List<String> {
            val event = scenario.eventType.wire
            return listOf(scenario.skuId, "cat:${scenario.category}").flatMap { scope ->
                listOf(
                    "$scope|${scenario.dayType.wire}|${scenario.weather.wire}|${scenario.stockLevel.wire}|$event",
                    "$scope|${scenario.dayType.wire}|${scenario.weather.wire}|${scenario.stockLevel.wire}",
                    "$scope|${scenario.dayType.wire}|${scenario.stockLevel.wire}",
                    scope,
                )
            }
        }

        fun stringLessons(
            cases: List<Pair<PromotionScenario, Map<Discount, BigDecimal>>>,
            buckets: (PromotionScenario) -> List<String>,
        ): Map<String, Discount> =
            cases
                .flatMap { (scenario, profits) -> buckets(scenario).map { it to profits } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, group) ->
                    best(
                        Discount.entries.associateWith { d ->
                            group.fold(BigDecimal.ZERO) { sum, p -> sum.add(p.getValue(d)) }
                        },
                    )
                }

        fun reportStrings(
            cases: List<Pair<PromotionScenario, Map<Discount, BigDecimal>>>,
            table: Map<String, Discount>,
            buckets: (PromotionScenario) -> List<String>,
            label: String,
        ) {
            val advised = cases.map { (s, p) -> buckets(s).firstNotNullOfOrNull { table[it] } to p }
            val covered = advised.filter { it.first != null }
            val optimal = covered.count { (advice, p) -> advice == best(p) }
            val loss =
                advised.fold(BigDecimal.ZERO) { sum, (advice, p) ->
                    sum.add(p.getValue(best(p)).subtract(p.getValue(advice ?: Discount.NONE)))
                }
            println(
                "%-28s %6d/%-3d %7s%%  %11s".format(
                    label,
                    covered.size,
                    cases.size,
                    if (covered.isEmpty()) "-" else percent(optimal, covered.size),
                    loss.setScale(2, RoundingMode.HALF_UP),
                ),
            )
        }

        println("Loss is the gross profit given up against the best action; lower is better.")
        println("\nValidation: learned from the first ${fit.size} training scenarios, measured on")
        println("the last ${validation.size} of them - a slice no scheme was chosen on.\n")
        println("policy                       coverage  optimal        loss")
        reportOn(validation, lessonsFrom(fit), "exact conditions only", strictOnly = true)
        reportOn(validation, lessonsFrom(fit), "cascade (implemented)", strictOnly = false)

        println("\nHeld-out: learned from all ${training.size} training scenarios, measured on")
        println("${benchmark.size} scenarios after the split date.\n")
        println("policy                       coverage  optimal        loss")
        report("exact conditions only", strictOnly = true)
        report("cascade (implemented)", strictOnly = false)
        println("\nbuckets filled by training: ${lessons.size}")

        println("\nDoes putting the event back into the key pay? Same two slices.\n")
        println("policy                       coverage  optimal        loss")
        val fitEvent = stringLessons(fit, ::eventAwareBuckets)
        reportStrings(validation, fitEvent, ::eventAwareBuckets, "validation, event in key")
        val allEvent = stringLessons(training, ::eventAwareBuckets)
        reportStrings(benchmark, allEvent, ::eventAwareBuckets, "held-out, event in key")
        val events = benchmark.count { (s, _) -> s.eventType.wire != "none" }
        println("\nheld-out scenarios carrying a local event: $events of ${benchmark.size}")
    }

    private const val MOST_SPECIFIC = 3

    /** Scenarios cut off the end of the training set, by time, to choose the scheme on. */
    private const val VALIDATION = 50

    private fun percent(
        part: Int,
        whole: Int,
    ): String = BigDecimal(part * 100).divide(BigDecimal(whole), 1, RoundingMode.HALF_UP).toString()
}
