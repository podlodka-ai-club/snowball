package club.podlodka.snowball.port

import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.Lesson
import club.podlodka.snowball.domain.PromotionScenario

/** What the model was asked and what it answered, kept so a run can be defended afterwards. */
data class ModelDecision(
    val discount: Discount,
    val modelId: String,
    val rawAnswer: String,
)

/**
 * Asks a model to choose one allowed discount.
 *
 * The port exists so the agent does not depend on a provider, and so tests can drive every
 * failure path - a refusal, a malformed answer, a discount outside the allowed four - without a
 * network call.
 */
interface DecisionModel {
    val modelId: String

    /**
     * Returns the chosen discount, or null when the answer could not be understood. Deciding what
     * to do about that - retry, fall back - belongs to the caller, because it is a policy question
     * and it has to be visible in the metrics.
     */
    fun choose(
        scenario: PromotionScenario,
        lessons: List<Lesson>,
    ): ModelDecision?
}
