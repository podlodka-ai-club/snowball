package club.podlodka.snowball.adapter.memory

import club.podlodka.snowball.domain.CaseEvidence
import club.podlodka.snowball.domain.Lesson
import club.podlodka.snowball.domain.LessonKey
import club.podlodka.snowball.domain.PromotionCase
import club.podlodka.snowball.port.LearningMemory

/**
 * Keeps the evidence behind each lesson in this process, so a run reads it from the service once
 * rather than once per scenario.
 *
 * The reason is quota, not only speed. Aggregating a lesson means walking the `lesson_evidence`
 * relation, and a traversal is a natural-language read: it spends model tokens from an allowance
 * the whole team shares, and takes about twenty seconds on a key the service has not answered for
 * before. At two lessons per scenario that is some five hundred model calls and four hours over a
 * training run, to re-read facts this same process just wrote.
 *
 * What is durable does not change: cases and lessons are still written to the service, and the
 * agent still reads its lessons from there. Only the aggregation input is served locally, and it
 * is seeded from the service - so a resumed run sees everything earlier runs recorded.
 */
class CachingEvidenceMemory(
    private val delegate: LearningMemory,
    private val loadAll: () -> Map<String, List<CaseEvidence>>,
) : LearningMemory {
    private val evidence = mutableMapOf<String, MutableList<CaseEvidence>>()
    private val cases = mutableMapOf<String, CaseEvidence>()
    private var seeded = false

    override fun casesFor(key: LessonKey): List<CaseEvidence> {
        seed()
        return evidence[key.wire].orEmpty()
    }

    override fun findCase(caseId: String): PromotionCase? = delegate.findCase(caseId)

    override fun saveCase(case: PromotionCase) {
        delegate.saveCase(case)
        cases[case.caseId] = CaseEvidence(case.caseId, case.profitByDiscount, case.bestDiscount)
    }

    override fun linkCaseToLesson(
        caseId: String,
        key: LessonKey,
    ) {
        delegate.linkCaseToLesson(caseId, key)
        seed()
        val known = cases[caseId] ?: return
        val bucket = evidence.getOrPut(key.wire) { mutableListOf() }
        // Re-running a scenario re-links the same case; the evidence set is a set, not a tally.
        if (bucket.none { it.caseId == caseId }) {
            bucket += known
        }
    }

    override fun saveLesson(lesson: Lesson) = delegate.saveLesson(lesson)

    override fun lesson(key: LessonKey): Lesson? = delegate.lesson(key)

    /**
     * Loads the recorded evidence once, on first use rather than at construction: a run that never
     * aggregates anything - a benchmark arm with learning off - should not pay for it at all.
     */
    private fun seed() {
        if (seeded) return
        seeded = true
        loadAll().forEach { (key, cases) -> evidence[key] = cases.toMutableList() }
    }
}
