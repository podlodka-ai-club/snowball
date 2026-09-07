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
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * What the memory is worth when the learner is not allowed to see the oracle.
 *
 * The audit's sharpest finding was not that the market is synthetic - it is that the learner reads
 * the profit of all four discounts, including the three that were never chosen. No real shop can
 * know those. A stronger version of the same objection: because a lesson aggregates the four oracle
 * profits, what the agent decided never affects what the memory learns, so the loop "act, observe,
 * improve" is only closed in one direction.
 *
 * Here the learner is blinded. It sees exactly one number per scenario - the profit of the action
 * that was actually taken - and the oracle is used solely to score the result afterwards. That is
 * the information a shop really has. Whatever survives this is a claim about a mechanism rather
 * than about a simulator.
 *
 * Everything runs offline: the simulator is deterministic, so acting, observing and scoring all
 * reproduce locally without the model and without spending memory quota.
 */
object AnalyzeBlindLearner {
    /** Scenarios cut off the end of the training set, by time, to score the learners on. */
    private const val VALIDATION = 50

    /** How strongly an untried action is preferred. Standard UCB1 exploration weight. */
    private const val EXPLORATION = 2.0

    /** Visits a bucket needs before it is trusted to pick the action. */
    private const val MIN_EVIDENCE = 4

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

        /** The four ground-truth profits. Available to the scorer; withheld from the blind learner. */
        fun profits(event: PromotionScenarioEvent): Map<Discount, BigDecimal> =
            Discount.entries.associateWith { engine.simulate(event.scenarioId, event.scenario, it).grossProfit }

        val all = events.map { it to profits(it) }
        val fit = all.dropLast(VALIDATION)
        val validation = all.takeLast(VALIDATION)

        fun best(byDiscount: Map<Discount, BigDecimal>): Discount =
            Discount.entries
                .sortedWith(compareByDescending<Discount> { byDiscount.getValue(it) }.thenBy { it.percent })
                .first()

        // The oracle learner, as built today: every bucket sums the four counterfactual profits.
        fun oracleLessons(cases: List<Pair<PromotionScenarioEvent, Map<Discount, BigDecimal>>>): Map<String, Discount> =
            cases
                .flatMap { (event, p) -> LessonKey.bucketsFor(event.scenario).map { it.wire to p } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, group) ->
                    best(
                        Discount.entries.associateWith { d ->
                            group.fold(BigDecimal.ZERO) { sum, p -> sum.add(p.getValue(d)) }
                        },
                    )
                }

        /**
         * The blind learner: one observation per scenario, for the action actually taken.
         *
         * Acting and learning are the same pass here, which is the point - the agent's own choices
         * determine what it will know. Action selection is UCB1 over the most specific bucket that
         * has any history: an action nobody tried is always worth one try, and after that the
         * balance shifts to whatever has paid best. Exploration is what a real shop pays for
         * knowledge, and its cost shows up in the training regret rather than being hidden.
         */
        fun blindLearn(
            cases: List<Pair<PromotionScenarioEvent, Map<Discount, BigDecimal>>>,
        ): Pair<Map<String, Discount>, BigDecimal> {
            val total = mutableMapOf<Pair<String, Discount>, BigDecimal>()
            val tries = mutableMapOf<Pair<String, Discount>, Int>()
            var explorationCost = BigDecimal.ZERO

            cases.forEach { (event, oracle) ->
                val buckets = LessonKey.bucketsFor(event.scenario).map { it.wire }
                // Decide on the most specific bucket that has enough history to mean anything.
                // Blind learning splits thin: one observation per scenario spread over six buckets
                // and four actions, so a strict bucket with two visits is noise wearing a key.
                val decisionBucket =
                    buckets.firstOrNull { b ->
                        Discount.entries.sumOf { tries[b to it] ?: 0 } >= MIN_EVIDENCE
                    } ?: buckets.last()
                val seen = Discount.entries.sumOf { tries[decisionBucket to it] ?: 0 }
                val chosen =
                    Discount.entries.maxByOrNull { action ->
                        val n = tries[decisionBucket to action] ?: 0
                        if (n == 0) {
                            Double.MAX_VALUE
                        } else {
                            val mean = total.getValue(decisionBucket to action).toDouble() / n
                            mean + EXPLORATION * sqrt(ln(seen.toDouble().coerceAtLeast(1.0)) / n)
                        }
                    }!!

                // The only number the learner is allowed to see.
                val observed = oracle.getValue(chosen)
                buckets.forEach { bucket ->
                    total[bucket to chosen] = (total[bucket to chosen] ?: BigDecimal.ZERO).add(observed)
                    tries[bucket to chosen] = (tries[bucket to chosen] ?: 0) + 1
                }
                explorationCost = explorationCost.add(oracle.getValue(best(oracle)).subtract(observed))
            }

            val frozen =
                total.keys
                    .map { it.first }
                    .distinct()
                    .mapNotNull { bucket ->
                        val visits = Discount.entries.sumOf { tries[bucket to it] ?: 0 }
                        if (visits < MIN_EVIDENCE) return@mapNotNull null
                        val meanByAction =
                            Discount.entries.mapNotNull { action ->
                                val n = tries[bucket to action] ?: return@mapNotNull null
                                action to
                                    total.getValue(bucket to action).divide(BigDecimal(n), 6, RoundingMode.HALF_UP)
                            }
                        meanByAction.maxByOrNull { it.second }?.let { bucket to it.first }
                    }.toMap()
            return frozen to explorationCost
        }

        fun score(
            label: String,
            table: Map<String, Discount>,
        ) {
            val advised =
                validation.map { (event, p) ->
                    LessonKey.bucketsFor(event.scenario).firstNotNullOfOrNull { table[it.wire] } to p
                }
            val covered = advised.filter { it.first != null }
            val optimal = covered.count { (advice, p) -> advice == best(p) }
            val loss =
                advised.fold(BigDecimal.ZERO) { sum, (advice, p) ->
                    sum.add(p.getValue(best(p)).subtract(p.getValue(advice ?: Discount.NONE)))
                }
            println(
                "%-34s %6d/%-3d %7s%%  %11s".format(
                    label,
                    covered.size,
                    validation.size,
                    if (covered.isEmpty()) "-" else percent(optimal, covered.size),
                    loss.setScale(2, RoundingMode.HALF_UP),
                ),
            )
        }

        println("Learners trained on the first ${fit.size} scenarios, scored on the last $VALIDATION.")
        println("Loss is the profit given up against the oracle on the scored slice; lower is better.\n")
        println("learner                          coverage  optimal        loss")

        val noMemory = emptyMap<String, Discount>()
        score("no memory (always 0%)", noMemory)
        score("oracle learner (as built)", oracleLessons(fit))
        val (blind, exploration) = blindLearn(fit)
        score("blind learner (observed only)", blind)

        println("\nExploration cost while learning blind: ${exploration.setScale(2, RoundingMode.HALF_UP)}")
        println("(profit given up during the ${fit.size} training scenarios - a real shop pays this)")

        println("\nHow the blind learner converges, scored on the same slice:\n")
        println("trained on                       coverage  optimal        loss")
        listOf(50, 100, 150, 200).filter { it <= fit.size }.forEach { n ->
            score("  first $n scenarios", blindLearn(fit.take(n)).first)
        }
    }

    private fun percent(
        part: Int,
        whole: Int,
    ): String = BigDecimal(part * 100).divide(BigDecimal(whole), 1, RoundingMode.HALF_UP).toString()
}
