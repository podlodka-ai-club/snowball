package club.podlodka.snowball.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Compact reusable knowledge, recomputed from every case linked to its key.
 *
 * A lesson is evidence, not a command: it carries how much evidence it rests on, how often that
 * evidence agreed, and how far ahead the recommendation actually is. The agent ranks lessons by
 * that strength rather than obeying the first one it reads.
 *
 * Nothing here is written by a model. `docs/xmemory/README.md` is explicit that no LLM chooses the
 * action, and the rationale is generated from the computed facts - a model may later polish the
 * sentence, but must not touch the numbers.
 */
data class Lesson(
    val key: LessonKey,
    val recommendedDiscount: Discount,
    val evidenceCount: Int,
    val avgProfitAdvantagePct: BigDecimal,
    val confidence: BigDecimal,
    val rationale: String,
) {
    init {
        require(evidenceCount > 0) { "a lesson without evidence is not a lesson" }
    }

    companion object {
        /**
         * Recompute a lesson from the unique cases linked to one key.
         *
         * The recommendation is the action with the highest total profit across the cases. Since
         * every case carries all four actions, that is the same as the highest mean, and exact
         * ties prefer the lower discount - a cheaper action that earns the same is the better
         * advice.
         */
        fun from(
            key: LessonKey,
            cases: List<CaseEvidence>,
        ): Lesson {
            require(cases.isNotEmpty()) { "cannot compute a lesson for $key without cases" }
            val unique = cases.distinctBy { it.caseId }
            val count = unique.size

            val totals =
                Discount.entries.associateWith { discount ->
                    unique.fold(BigDecimal.ZERO) { sum, case -> sum.add(case.profitByDiscount.getValue(discount)) }
                }
            val recommended =
                Discount.entries
                    .sortedWith(compareByDescending<Discount> { totals.getValue(it) }.thenBy { it.percent })
                    .first()

            val meanRecommended = mean(totals.getValue(recommended), count)
            val bestAlternative =
                Discount.entries
                    .filter { it != recommended }
                    .maxOf { mean(totals.getValue(it), count) }
            val advantage =
                meanRecommended
                    .subtract(bestAlternative)
                    .divide(bestAlternative.abs().max(BigDecimal("0.01")), 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal(100))
                    .setScale(2, RoundingMode.HALF_UP)

            return Lesson(
                key = key,
                recommendedDiscount = recommended,
                evidenceCount = count,
                avgProfitAdvantagePct = advantage,
                confidence = confidence(unique, recommended, advantage),
                rationale = rationale(key, recommended, count, advantage),
            )
        }

        /**
         * Quantity of evidence dominates, repeated agreement matters next, and profit separation
         * helps but cannot make a single anecdote look like certainty.
         */
        private fun confidence(
            cases: List<CaseEvidence>,
            recommended: Discount,
            advantagePct: BigDecimal,
        ): BigDecimal {
            val evidenceScore = minOf(cases.size.toDouble() / 5.0, 1.0)
            val agreementScore = cases.count { it.bestDiscount == recommended }.toDouble() / cases.size
            val advantageScore = (advantagePct.toDouble() / 10.0).coerceIn(0.0, 1.0)
            val score = 0.60 * evidenceScore + 0.25 * agreementScore + 0.15 * advantageScore
            return BigDecimal(score).setScale(2, RoundingMode.HALF_UP)
        }

        private fun rationale(
            key: LessonKey,
            recommended: Discount,
            count: Int,
            advantagePct: BigDecimal,
        ): String =
            "For ${key.scope.prefix}:${key.scopeValue} on ${key.weather.wire} ${key.dayType.wire} with " +
                "${key.stockLevel.wire} stock, ${recommended.percent}% has the highest mean gross profit across " +
                "$count evaluated ${if (count == 1) "case" else "cases"}, beating the next-best action by " +
                "$advantagePct%."

        private fun mean(
            total: BigDecimal,
            count: Int,
        ): BigDecimal = total.divide(BigDecimal(count), 6, RoundingMode.HALF_UP)
    }
}
