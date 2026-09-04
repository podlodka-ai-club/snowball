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

        val training = scenarios(DatasetSplit.TRAINING).map { it.scenario to profits(it) }
        val benchmark = scenarios(DatasetSplit.BENCHMARK).map { it.scenario to profits(it) }

        // Ties prefer the lower discount, exactly as the learner does.
        fun best(byDiscount: Map<Discount, BigDecimal>): Discount =
            Discount.entries
                .sortedWith(compareByDescending<Discount> { byDiscount.getValue(it) }.thenBy { it.percent })
                .first()

        /** Lessons for every bucket the training set fills, exactly as the learner would write them. */
        val lessons: Map<String, Discount> =
            training
                .flatMap { (scenario, profits) -> LessonKey.bucketsFor(scenario).map { it to profits } }
                .groupBy({ it.first.wire }, { it.second })
                .mapValues { (_, cases) ->
                    best(
                        Discount.entries.associateWith { discount ->
                            cases.fold(BigDecimal.ZERO) { sum, p -> sum.add(p.getValue(discount)) }
                        },
                    )
                }

        /** What the agent would be told: strictest bucket per scope that has an answer. */
        fun advise(
            scenario: PromotionScenario,
            strictOnly: Boolean,
        ): Discount? =
            LessonKey
                .bucketsFor(scenario)
                .filter { !strictOnly || it.specificity == MOST_SPECIFIC }
                .groupBy { it.scope }
                .values
                .firstNotNullOfOrNull { buckets -> buckets.firstNotNullOfOrNull { lessons[it.wire] } }

        fun report(
            label: String,
            strictOnly: Boolean,
        ) {
            val advised = benchmark.map { (s, p) -> advise(s, strictOnly) to p }
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
                    benchmark.size,
                    if (covered.isEmpty()) "-" else percent(optimal, covered.size),
                    loss.setScale(2, RoundingMode.HALF_UP),
                ),
            )
        }

        println("Advice measured on ${benchmark.size} held-out scenarios, learned from ${training.size}.")
        println("Loss is the gross profit given up against the best action; lower is better.\n")
        println("policy                       coverage  optimal        loss")
        report("exact conditions only", strictOnly = true)
        report("cascade (implemented)", strictOnly = false)
        println("\nbuckets filled by training: ${lessons.size}")
    }

    private const val MOST_SPECIFIC = 3

    private fun percent(
        part: Int,
        whole: Int,
    ): String = BigDecimal(part * 100).divide(BigDecimal(whole), 1, RoundingMode.HALF_UP).toString()
}
