package club.podlodka.snowball.domain

import java.math.BigDecimal

/** The enriched decision context: everything a scenario needs that the baseline row lacks. */
data class ScenarioContext(
    val dayType: DayType,
    val weather: Weather,
    val temperatureC: BigDecimal,
    val eventType: MarketEvent,
    val eventNote: String? = null,
) {
    init {
        require(eventNote == null || eventNote.isNotEmpty()) { "event_note must not be empty" }
        require(eventType != MarketEvent.NONE || eventNote == null) {
            "event_note belongs to a local_event, not to a scenario without one"
        }
    }
}
