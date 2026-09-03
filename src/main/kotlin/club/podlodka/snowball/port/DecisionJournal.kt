package club.podlodka.snowball.port

import club.podlodka.snowball.domain.PromotionDecisionEvent

/** How far one scenario has got through the agent. */
enum class DecisionStatus {
    /** The decision exists and is persisted, but the handoff has not been confirmed. */
    DECIDED,

    /** The decision was handed off successfully; the scenario is finished. */
    COMPLETED,
}

/** A decision that survived a restart, and how far it had got. */
data class JournalEntry(
    val status: DecisionStatus,
    val decision: PromotionDecisionEvent,
)

/**
 * Operational memory: what the agent already decided, keyed by scenario.
 *
 * This is what makes a retry cheap and honest. A scenario already `COMPLETED` must not reach the
 * model again - not only to save a call, but because a second answer would be a second decision
 * for a promotion that already happened. A `DECIDED` scenario republishes exactly what was stored
 * rather than asking again, so a restart cannot change history.
 *
 * Not learning memory: this holds bookkeeping, and clearing it is a different act from clearing
 * what the agent has learned.
 */
interface DecisionJournal {
    fun find(scenarioId: String): JournalEntry?

    fun recordDecided(decision: PromotionDecisionEvent)

    fun markCompleted(scenarioId: String)
}
