package club.podlodka.snowball.adapter.memory

import club.podlodka.snowball.domain.PromotionDecisionEvent
import club.podlodka.snowball.port.DecisionJournal
import club.podlodka.snowball.port.DecisionStatus
import club.podlodka.snowball.port.JournalEntry

/**
 * A journal that lives as long as the process.
 *
 * Enough to make idempotency real within a run - a repeated scenario costs no model call and
 * produces no second decision. It is not enough to survive a restart, which the durable
 * implementation in `implement-promotion-agent` is for; that task stays open rather than being
 * quietly claimed.
 */
class InMemoryDecisionJournal : DecisionJournal {
    private val entries = linkedMapOf<String, JournalEntry>()

    override fun find(scenarioId: String): JournalEntry? = entries[scenarioId]

    override fun recordDecided(decision: PromotionDecisionEvent) {
        entries[decision.scenarioId] = JournalEntry(DecisionStatus.DECIDED, decision)
    }

    override fun markCompleted(scenarioId: String) {
        entries[scenarioId]?.let { entries[scenarioId] = it.copy(status = DecisionStatus.COMPLETED) }
    }

    val size: Int get() = entries.size
}
