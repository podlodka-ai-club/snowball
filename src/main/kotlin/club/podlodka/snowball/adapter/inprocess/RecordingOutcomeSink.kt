package club.podlodka.snowball.adapter.inprocess

import club.podlodka.snowball.domain.PromotionOutcomeEvent
import club.podlodka.snowball.port.OutcomeSink

/**
 * An [OutcomeSink] that keeps everything it accepts in memory, in acceptance order.
 */
class RecordingOutcomeSink : OutcomeSink {
    private val accepted = mutableListOf<PromotionOutcomeEvent>()

    val received: List<PromotionOutcomeEvent> get() = accepted.toList()

    override fun accept(outcome: PromotionOutcomeEvent) {
        accepted += outcome
    }
}
