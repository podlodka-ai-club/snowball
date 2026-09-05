package club.podlodka.snowball.adapter.inprocess

import club.podlodka.snowball.domain.PromotionScenarioEvent
import club.podlodka.snowball.port.ScenarioPublisher

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
