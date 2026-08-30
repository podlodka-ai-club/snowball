package club.podlodka.snowball.ports.inprocess

import club.podlodka.snowball.contracts.PromotionScenarioEvent
import club.podlodka.snowball.ports.ScenarioPublisher

/**
 * A [ScenarioPublisher] that keeps everything it publishes in memory, in publication order.
 */
class RecordingScenarioPublisher : ScenarioPublisher {
    private val sent = mutableListOf<PromotionScenarioEvent>()

    val published: List<PromotionScenarioEvent> get() = sent.toList()

    override fun publish(scenario: PromotionScenarioEvent) {
        sent += scenario
    }
}
