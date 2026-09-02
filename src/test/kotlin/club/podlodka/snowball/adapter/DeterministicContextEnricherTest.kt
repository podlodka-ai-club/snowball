package club.podlodka.snowball.adapter

import club.podlodka.snowball.adapter.context.DeterministicContextEnricher
import club.podlodka.snowball.adapter.source.DatasetBaselineSource
import club.podlodka.snowball.domain.BaselineRecord
import club.podlodka.snowball.domain.DatasetSplit
import club.podlodka.snowball.domain.DayType
import club.podlodka.snowball.domain.MarketEvent
import club.podlodka.snowball.domain.Weather
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class DeterministicContextEnricherTest {
    private val enricher = DeterministicContextEnricher()

    private fun fixture() =
        DatasetBaselineSource { javaClass.getResourceAsStream("/fixtures/baseline.csv")!!.reader() }
            .load()
            .records

    private fun record(
        date: String,
        sku: String = "ICE500",
    ) = BaselineRecord(
        sourceReference = "ref-$sku-$date",
        date = LocalDate.parse(date),
        split = DatasetSplit.TRAINING,
        skuId = sku,
        skuName = "Ice Cream 500ml",
        category = "ice_cream",
        price = BigDecimal("5.00"),
        cost = BigDecimal("3.00"),
        baselineSales = 100,
        stock = 150,
    )

    private fun describe(date: String): String {
        val context = enricher.enrich(record(date))
        return "${context.dayType}|${context.weather}|${context.temperatureC}|${context.eventType}"
    }

    @Test
    fun `day type follows the calendar`() {
        assertThat(enricher.enrich(record("2026-06-05")).dayType).isEqualTo(DayType.WEEKDAY)
        assertThat(enricher.enrich(record("2026-06-06")).dayType).isEqualTo(DayType.WEEKEND)
        assertThat(enricher.enrich(record("2026-06-07")).dayType).isEqualTo(DayType.WEEKEND)
    }

    @Test
    fun `enrichment is pinned to exact values, not merely self-consistent`() {
        // Two calls inside one JVM cannot tell a pure function from one keyed on an identity hash
        // or the clock - both are stable within a single run. These values come from the committed
        // derivation and change the moment it does, which is what makes the cross-process promise
        // checkable in a single process.
        val golden =
            mapOf(
                "2026-06-10" to "WEEKDAY|HOT|27|NONE",
                "2026-06-13" to "WEEKEND|RAIN|7|LOCAL_EVENT",
                "2026-06-20" to "WEEKEND|NORMAL|16|NONE",
            )

        golden.forEach { (date, expected) ->
            assertThat(describe(date)).describedAs("enrichment of %s", date).isEqualTo(expected)
        }
    }

    @Test
    fun `temperature agrees with the weather`() {
        fixture().map { enricher.enrich(it) }.forEach { context ->
            val degrees = context.temperatureC.toInt()
            when (context.weather) {
                Weather.HOT -> assertThat(degrees).isGreaterThanOrEqualTo(24)
                Weather.RAIN -> assertThat(degrees).isLessThanOrEqualTo(15)
                Weather.NORMAL -> assertThat(degrees).isBetween(12, 22)
            }
        }
    }

    @Test
    fun `an event note accompanies a local event and nothing else`() {
        fixture().map { enricher.enrich(it) }.forEach { context ->
            if (context.eventType == MarketEvent.LOCAL_EVENT) {
                assertThat(context.eventNote).isNotNull()
            } else {
                assertThat(context.eventNote).isNull()
            }
        }
    }

    @Test
    fun `both halves of the committed fixture cover the Lesson key space`() {
        val records = fixture()

        DatasetSplit.entries.forEach { split ->
            val contexts = records.filter { it.split == split }.map { enricher.enrich(it) }
            assertThat(contexts.map { it.weather }.toSet())
                .describedAs("weather values in %s", split)
                .containsExactlyInAnyOrderElementsOf(Weather.entries)
            assertThat(contexts.map { it.eventType }.toSet())
                .describedAs("event values in %s", split)
                .containsExactlyInAnyOrderElementsOf(MarketEvent.entries)
        }
    }
}
