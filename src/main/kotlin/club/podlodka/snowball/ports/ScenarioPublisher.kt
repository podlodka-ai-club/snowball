package club.podlodka.snowball.ports

import club.podlodka.snowball.contracts.PromotionScenarioEvent

/**
 * Accepts a generated scenario from the Scenario Generator.
 *
 * As with [OutcomeSink], the transport is deliberately absent from the signature.
 */
fun interface ScenarioPublisher {
    fun publish(scenario: PromotionScenarioEvent)
}
