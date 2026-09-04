package club.podlodka.snowball.application

import club.podlodka.snowball.domain.Lesson
import club.podlodka.snowball.domain.LessonKey
import club.podlodka.snowball.domain.LessonScope
import club.podlodka.snowball.domain.PromotionScenario

/**
 * Decides which lessons reach the prompt.
 *
 * Eligibility stays in application code rather than in the memory query, so it is predictable and
 * testable: a lesson whose context does not match the scenario is discarded no matter how the
 * store ranked it. Ordering is fully deterministic down to the key, because the prompt is an
 * experiment input - two runs over the same memory must produce the same prompt or the comparison
 * means nothing.
 */
object LessonRanker {
    const val MAX_LESSONS_IN_PROMPT = 3

    fun eligible(
        scenario: PromotionScenario,
        candidates: List<Lesson>,
    ): List<Lesson> {
        val wanted = LessonKey.bucketsFor(scenario).toSet()
        return candidates
            .filter { it.key in wanted }
            .sortedWith(
                // Exact SKU before category: advice about this product beats advice about its
                // family. Then strength, then the key itself so ties cannot reorder between runs.
                compareBy<Lesson> { if (it.key.scope == LessonScope.SKU) 0 else 1 }
                    // Then the bucket that pins down more conditions: advice about this weather on
                    // this kind of day beats advice about the product in general.
                    .thenByDescending { it.key.specificity }
                    .thenByDescending { it.confidence }
                    .thenByDescending { it.evidenceCount }
                    .thenByDescending { it.avgProfitAdvantagePct }
                    .thenBy { it.key.wire },
            ).take(MAX_LESSONS_IN_PROMPT)
    }
}
