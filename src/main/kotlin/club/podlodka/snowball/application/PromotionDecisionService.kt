package club.podlodka.snowball.application

import club.podlodka.snowball.domain.ContractValidator
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.Lesson
import club.podlodka.snowball.domain.LessonKey
import club.podlodka.snowball.domain.PromotionDecision
import club.podlodka.snowball.domain.PromotionDecisionEvent
import club.podlodka.snowball.domain.PromotionScenarioEvent
import club.podlodka.snowball.port.DecisionJournal
import club.podlodka.snowball.port.DecisionModel
import club.podlodka.snowball.port.DecisionSink
import club.podlodka.snowball.port.DecisionStatus
import club.podlodka.snowball.port.LearningMemory
import java.time.Clock
import java.time.OffsetDateTime
import java.util.logging.Logger

/** Where a decision came from, which the benchmark must be able to count separately. */
enum class DecisionSource {
    /** The model chose it. */
    MODEL,

    /** Both model attempts failed, so the deterministic default was used. */
    FALLBACK,

    /** Recovered from the journal rather than decided again. */
    JOURNAL,
}

/** Whether memory could be consulted, recorded so a run can be interpreted afterwards. */
enum class MemoryStatus {
    USED,
    EMPTY,
    UNAVAILABLE,
}

data class DecisionOutcome(
    val decision: PromotionDecisionEvent,
    val source: DecisionSource,
    val memoryStatus: MemoryStatus,
    val lessonsUsed: List<LessonKey>,
)

/**
 * Turns one scenario into one validated decision, using whatever the memory can offer.
 *
 * The agent never sees the simulator. It has no `SimulationPort`, so it cannot replay actions and
 * discover what each would have earned - that is the ground truth it is supposed to learn from
 * experience rather than read. Everything it knows comes from the scenario and from lessons other
 * runs left behind.
 */
class PromotionDecisionService(
    private val memory: LearningMemory,
    private val model: DecisionModel,
    private val journal: DecisionJournal,
    private val decisions: DecisionSink,
    private val clock: Clock = Clock.systemUTC(),
    private val validate: (PromotionDecisionEvent) -> Unit = ContractValidator::validateDecision,
    private val validateInput: (PromotionScenarioEvent) -> Unit = ContractValidator::validateScenario,
) {
    fun decide(scenario: PromotionScenarioEvent): DecisionOutcome {
        // Before anything with a consequence. A scenario that violates its contract must not reach
        // the journal, the memory or the model: a rejected input should cost nothing and leave no
        // trace, while a half-processed one leaves a journal entry that a later run would faithfully
        // republish, and spends model tokens deciding on numbers already known to be wrong.
        validateInput(scenario)

        journal.find(scenario.scenarioId)?.let { entry ->
            // A finished scenario must not be decided twice, and a half-finished one republishes
            // exactly what was stored: a restart cannot be allowed to change a decision that was
            // already made and possibly already acted on.
            if (entry.status == DecisionStatus.COMPLETED) {
                log.info { "scenario_id=${scenario.scenarioId} already completed; no memory or model call" }
                return DecisionOutcome(entry.decision, DecisionSource.JOURNAL, MemoryStatus.EMPTY, emptyList())
            }
            decisions.accept(entry.decision)
            journal.markCompleted(scenario.scenarioId)
            log.info { "scenario_id=${scenario.scenarioId} republished from journal without asking the model" }
            return DecisionOutcome(entry.decision, DecisionSource.JOURNAL, MemoryStatus.EMPTY, emptyList())
        }

        val (lessons, memoryStatus) = recall(scenario)
        val chosen = ask(scenario, lessons)

        val decision =
            PromotionDecisionEvent(
                scenarioEvent = scenario,
                decisionId = "DEC-${scenario.scenarioId}",
                decidedAt = OffsetDateTime.now(clock),
                decision = PromotionDecision(chosen.first),
            )
        validate(decision)

        // Persist before publish: if the handoff fails, recovery republishes this exact payload
        // rather than asking the model for a second opinion.
        journal.recordDecided(decision)
        decisions.accept(decision)
        journal.markCompleted(scenario.scenarioId)

        log.info {
            "scenario_id=${scenario.scenarioId} decision_id=${decision.decisionId} " +
                "discount=${chosen.first.percent} source=${chosen.second} memory=$memoryStatus " +
                "lessons=${lessons.map { it.key.wire }} model=${model.modelId}"
        }
        return DecisionOutcome(decision, chosen.second, memoryStatus, lessons.map { it.key })
    }

    /**
     * Memory is advisory: if it cannot be reached, the agent decides without it and says so.
     *
     * The buckets are consulted strictest first and the search stops at the first hit within each
     * scope. That is what makes the cascade affordable - a looser bucket is only read when the
     * precise one has nothing, so a well-covered scenario costs the same two reads it always did,
     * and reads are the expensive half of this memory.
     */
    private fun recall(scenario: PromotionScenarioEvent): Pair<List<Lesson>, MemoryStatus> =
        try {
            val candidates =
                LessonKey
                    .bucketsFor(scenario.scenario)
                    .groupBy { it.scope }
                    .values
                    .mapNotNull { buckets -> buckets.firstNotNullOfOrNull(memory::lesson) }
            val eligible = LessonRanker.eligible(scenario.scenario, candidates)
            eligible to if (eligible.isEmpty()) MemoryStatus.EMPTY else MemoryStatus.USED
        } catch (failure: Exception) {
            log.warning { "memory unavailable for ${scenario.scenarioId}: ${failure.message}" }
            emptyList<Lesson>() to MemoryStatus.UNAVAILABLE
        }

    /**
     * One retry, then the deterministic default.
     *
     * The fallback is recorded as its own source rather than blending into the numbers: a run
     * where the model kept failing would otherwise look like a run where it kept choosing 0%, and
     * the benchmark would be measuring an outage.
     */
    private fun ask(
        scenario: PromotionScenarioEvent,
        lessons: List<Lesson>,
    ): Pair<Discount, DecisionSource> {
        repeat(2) { attempt ->
            model.choose(scenario.scenario, lessons)?.let { return it.discount to DecisionSource.MODEL }
            log.warning { "model attempt ${attempt + 1} failed for ${scenario.scenarioId}" }
        }
        return Discount.NONE to DecisionSource.FALLBACK
    }

    private companion object {
        private val log: Logger = Logger.getLogger(PromotionDecisionService::class.java.name)
    }
}
