package club.podlodka.snowball.adapter.memory

import club.podlodka.snowball.domain.Lesson
import club.podlodka.snowball.domain.LessonKey
import club.podlodka.snowball.domain.PromotionCase
import club.podlodka.snowball.port.LearningMemory

/**
 * Learning memory that lives for the length of one process.
 *
 * Not a stand-in for xmemory in production terms - it forgets everything on exit, and persistence
 * across restarts is one of the four things the project is required to demonstrate. What it is
 * good for is developing and testing the learning algorithm without spending quota, and giving the
 * evaluator something real to write into before the durable client exists.
 */
class InMemoryLearningMemory : LearningMemory {
    private val cases = linkedMapOf<String, PromotionCase>()
    private val links = linkedMapOf<LessonKey, MutableSet<String>>()
    private val lessons = linkedMapOf<LessonKey, Lesson>()

    override fun casesFor(key: LessonKey): List<PromotionCase> = links[key].orEmpty().mapNotNull { cases[it] }

    override fun findCase(caseId: String): PromotionCase? = cases[caseId]

    override fun saveCase(case: PromotionCase) {
        cases[case.caseId] = case
    }

    override fun linkCaseToLesson(
        caseId: String,
        key: LessonKey,
    ) {
        links.getOrPut(key) { linkedSetOf() }.add(caseId)
    }

    override fun saveLesson(lesson: Lesson) {
        lessons[lesson.key] = lesson
    }

    override fun lesson(key: LessonKey): Lesson? = lessons[key]

    /** Everything learned so far, for tests and for showing the loop. */
    val allLessons: List<Lesson> get() = lessons.values.toList()

    val caseCount: Int get() = cases.size
}
