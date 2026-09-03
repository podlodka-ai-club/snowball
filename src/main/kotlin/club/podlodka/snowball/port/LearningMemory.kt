package club.podlodka.snowball.port

import club.podlodka.snowball.domain.CaseEvidence
import club.podlodka.snowball.domain.Lesson
import club.podlodka.snowball.domain.LessonKey
import club.podlodka.snowball.domain.PromotionCase

/**
 * Durable learning memory: evaluated cases and the lessons recomputed from them.
 *
 * Named for what it holds rather than for the product behind it. xmemory is the intended
 * implementation, but the evaluator must not depend on that - and an in-memory version is what
 * lets the learning algorithm be developed and tested without spending quota on every run.
 *
 * Reads are by key on purpose. A measured natural-language read costs 20-26 seconds and a model
 * call, which per scenario is both the wall-clock and the quota problem; see `GOTCHAS.md`.
 */
interface LearningMemory {
    /** Every case already linked to this bucket, so a lesson can be recomputed from all evidence. */
    fun casesFor(key: LessonKey): List<CaseEvidence>

    fun findCase(caseId: String): PromotionCase?

    fun saveCase(case: PromotionCase)

    fun linkCaseToLesson(
        caseId: String,
        key: LessonKey,
    )

    fun saveLesson(lesson: Lesson)

    fun lesson(key: LessonKey): Lesson?
}
