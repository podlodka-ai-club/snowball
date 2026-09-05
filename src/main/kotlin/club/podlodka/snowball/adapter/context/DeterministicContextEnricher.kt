package club.podlodka.snowball.adapter.context

import club.podlodka.snowball.domain.BaselineRecord
import club.podlodka.snowball.domain.DayType
import club.podlodka.snowball.domain.MarketEvent
import club.podlodka.snowball.domain.ScenarioContext
import club.podlodka.snowball.domain.Weather
import club.podlodka.snowball.port.ContextEnricher
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.DayOfWeek

/**
 * Derives the decision context the dataset does not carry.
 *
 * Everything here is a pure function of the row. `day_type` follows from the fixture date in the
 * market timezone, and weather and events come from a SHA-256 of the row identity - not from
 * `hashCode`, which is stable within a JVM session and worthless across runs, and not from a
 * random source, seeded or otherwise. Reproducible evals depend on this being boring.
 *
 * The weather distribution is deliberately non-uniform but covers every value: `weather` is part
 * of the Lesson key, and a value that never occurs removes a whole family of lessons from the
 * experiment.
 */
class DeterministicContextEnricher(
    private val salt: String = DEFAULT_SALT,
) : ContextEnricher {
    override fun enrich(record: BaselineRecord): ScenarioContext {
        val dayType =
            when (record.date.dayOfWeek) {
                DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> DayType.WEEKEND
                else -> DayType.WEEKDAY
            }
        val weather = WEATHER_BY_BUCKET[bucket("weather", record, WEATHER_BY_BUCKET.size)]
        val eventType =
            if (bucket("event", record, EVENT_DIVISOR) == 0) MarketEvent.LOCAL_EVENT else MarketEvent.NONE
        return ScenarioContext(
            dayType = dayType,
            weather = weather,
            temperatureC = temperatureFor(weather, record),
            eventType = eventType,
            eventNote = if (eventType == MarketEvent.LOCAL_EVENT) "local_event" else null,
        )
    }

    /** Temperature has to agree with the weather rather than contradict it. */
    private fun temperatureFor(
        weather: Weather,
        record: BaselineRecord,
    ): BigDecimal {
        val (low, high) =
            when (weather) {
                Weather.HOT -> 24 to 33
                Weather.RAIN -> 6 to 15
                Weather.NORMAL -> 12 to 22
            }
        val offset = bucket("temperature", record, high - low + 1)
        return BigDecimal(low + offset)
    }

    private fun bucket(
        purpose: String,
        record: BaselineRecord,
        modulo: Int,
    ): Int {
        val key = "$salt|$purpose|${record.skuId}|${record.date}|${record.sourceReference}"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (digest[index].toLong() and 0xff)
        }
        return ((value ushr 1) % modulo).toInt()
    }

    companion object {
        const val DEFAULT_SALT = "scenario-context-v1"

        /** Skewed towards normal weather, but hot and rain occur often enough to learn from. */
        private val WEATHER_BY_BUCKET =
            listOf(
                Weather.NORMAL,
                Weather.NORMAL,
                Weather.HOT,
                Weather.RAIN,
            )

        private const val EVENT_DIVISOR = 5
    }
}
