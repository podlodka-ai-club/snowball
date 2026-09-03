package club.podlodka.snowball.adapter.memory

import club.podlodka.snowball.application.EvaluationResult
import club.podlodka.snowball.application.PromotionEvaluator
import club.podlodka.snowball.domain.PromotionOutcomeEvent
import club.podlodka.snowball.port.OutcomeSink

/**
 * The joint between the two halves of the loop: what the simulator finishes, the evaluator picks
 * up.
 *
 * A deliberately thin adapter. The simulator's responsibility ends at the outcome, and everything
 * after it - replay, oracle, regret, lessons - belongs to the evaluator; this class only carries
 * the handoff, and holds no judgement of its own.
 *
 * Results are kept so a benchmark can read regret and learning without another transport being
 * introduced for the purpose.
 */
class EvaluatingOutcomeSink(
    private val evaluator: PromotionEvaluator,
) : OutcomeSink {
    private val evaluations = mutableListOf<EvaluationResult>()

    val results: List<EvaluationResult> get() = evaluations.toList()

    override fun accept(outcome: PromotionOutcomeEvent) {
        evaluations += evaluator.evaluate(outcome)
    }
}
