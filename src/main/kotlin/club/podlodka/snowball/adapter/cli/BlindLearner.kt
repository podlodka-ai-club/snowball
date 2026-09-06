package club.podlodka.snowball.adapter.cli

import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.LessonKey
import club.podlodka.snowball.domain.PromotionScenarioEvent
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ln
import kotlin.math.sqrt

/** What a blind learner knows about one bucket: means of what it observed, per action it tried. */
data class BlindBucket(
    val meanByAction: Map<Discount, BigDecimal>,
    val visits: Int,
) {
    val recommended: Discount get() = meanByAction.maxByOrNull { it.value }!!.key
}

/**
 * The learner that sees only the profit of the action it chose - the information a real shop has.
 *
 * Acting and learning are one pass: UCB1 picks an action on the most specific bucket with enough
 * history, the profit of that one action is observed, and every bucket the scenario belongs to is
 * updated for that action alone. The oracle is used by the caller to score the result afterwards
 * and never enters this function.
 */
object BlindLearner {
    private const val EXPLORATION = 2.0
    private const val MIN_EVIDENCE = 4

    fun learn(
        cases: List<Pair<PromotionScenarioEvent, Map<Discount, BigDecimal>>>,
    ): Pair<Map<String, BlindBucket>, BigDecimal> {
        val total = mutableMapOf<Pair<String, Discount>, BigDecimal>()
        val tries = mutableMapOf<Pair<String, Discount>, Int>()
        var explorationCost = BigDecimal.ZERO

        cases.forEach { (event, oracle) ->
            val buckets = LessonKey.bucketsFor(event.scenario).map { it.wire }
            val decisionBucket =
                buckets.firstOrNull { b -> Discount.entries.sumOf { tries[b to it] ?: 0 } >= MIN_EVIDENCE }
                    ?: buckets.last()
            val seen = Discount.entries.sumOf { tries[decisionBucket to it] ?: 0 }
            val chosen =
                Discount.entries.maxByOrNull { action ->
                    val n = tries[decisionBucket to action] ?: 0
                    if (n == 0) {
                        Double.MAX_VALUE
                    } else {
                        total.getValue(decisionBucket to action).toDouble() / n +
                            EXPLORATION * sqrt(ln(seen.toDouble().coerceAtLeast(1.0)) / n)
                    }
                }!!
            val observed = oracle.getValue(chosen)
            buckets.forEach { bucket ->
                total[bucket to chosen] = (total[bucket to chosen] ?: BigDecimal.ZERO).add(observed)
                tries[bucket to chosen] = (tries[bucket to chosen] ?: 0) + 1
            }
            val best = oracle.maxByOrNull { it.value }!!.value
            explorationCost = explorationCost.add(best.subtract(observed))
        }

        val frozen =
            total.keys
                .map { it.first }
                .distinct()
                .mapNotNull { bucket ->
                    val visits = Discount.entries.sumOf { tries[bucket to it] ?: 0 }
                    if (visits < MIN_EVIDENCE) return@mapNotNull null
                    val means =
                        Discount.entries
                            .mapNotNull { action ->
                                val n = tries[bucket to action] ?: return@mapNotNull null
                                val mean =
                                    total.getValue(bucket to action).divide(BigDecimal(n), 6, RoundingMode.HALF_UP)
                                action to mean
                            }.toMap()
                    bucket to BlindBucket(means, visits)
                }.toMap()
        return frozen to explorationCost
    }
}
