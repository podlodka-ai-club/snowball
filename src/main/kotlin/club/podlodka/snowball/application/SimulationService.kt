package club.podlodka.snowball.application

import club.podlodka.snowball.config.SimulatorV1Config
import club.podlodka.snowball.domain.ContractValidator
import club.podlodka.snowball.domain.PromotionDecisionEvent
import club.podlodka.snowball.domain.PromotionOutcomeEvent
import club.podlodka.snowball.port.OutcomeSink
import club.podlodka.snowball.port.SimulationPort
import java.time.Clock
import java.time.OffsetDateTime
import java.util.logging.Logger

/**
 * Turns one validated decision into one outcome and hands it downstream.
 *
 * Its scope ends at `PromotionOutcomeV1`. It does not pick an oracle action, calculate regret,
 * build cases or touch memory - the Evaluator does all of that, using the same pure engine for
 * counterfactual replay.
 */
class SimulationService(
    private val simulation: SimulationPort,
    private val outcomeSink: OutcomeSink,
    private val clock: Clock = Clock.systemUTC(),
    private val validate: (PromotionOutcomeEvent) -> Unit = ContractValidator::validateOutcome,
) {
    fun simulate(decision: PromotionDecisionEvent): PromotionOutcomeEvent {
        require(decision.scenario.category in SimulatorV1Config.SUPPORTED_CATEGORIES) {
            "decision ${decision.decisionId} names category '${decision.scenario.category}', " +
                "which simulator ${SimulatorV1Config.VERSION} has no coefficients for"
        }

        val outcome =
            simulation.simulate(
                scenarioId = decision.scenarioId,
                scenario = decision.scenario,
                discount = decision.decision.discount,
            )
        val event =
            PromotionOutcomeEvent(
                decisionEvent = decision,
                outcomeId = "OUT-${decision.decisionId}",
                simulatedAt = OffsetDateTime.now(clock),
                outcome = outcome,
            )
        validate(event)

        // Handing off before considering the work done: the guide is explicit that a failed
        // handoff must not be reported as a completed simulation, so an exception here propagates
        // rather than being swallowed into a success.
        outcomeSink.accept(event)
        log.info {
            "simulated scenario_id=${decision.scenarioId} decision_id=${decision.decisionId} " +
                "outcome_id=${event.outcomeId} discount=${decision.decision.discount.percent} " +
                "units=${outcome.unitsSold} profit=${outcome.grossProfit}"
        }
        return event
    }

    private companion object {
        private val log: Logger = Logger.getLogger(SimulationService::class.java.name)
    }
}
