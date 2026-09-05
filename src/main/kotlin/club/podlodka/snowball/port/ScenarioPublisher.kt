package club.podlodka.snowball.port

import club.podlodka.snowball.domain.PromotionScenarioEvent

/**
 * Accepts a generated scenario from the Scenario Generator.
 *
 * As with [OutcomeSink], the transport is deliberately absent from the signature.
 */
fun interface ScenarioPublisher {
    fun publish(scenario: PromotionScenarioEvent)
}
