package club.podlodka.snowball.ports.inprocess

import club.podlodka.snowball.contracts.PromotionOutcomeEvent
import club.podlodka.snowball.ports.OutcomeSink

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
