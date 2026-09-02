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

    @Test
    fun `day type follows the calendar`() {
        assertThat(enricher.enrich(record("2026-06-05")).dayType).isEqualTo(DayType.WEEKDAY)
        assertThat(enricher.enrich(record("2026-06-06")).dayType).isEqualTo(DayType.WEEKEND)
        assertThat(enricher.enrich(record("2026-06-07")).dayType).isEqualTo(DayType.WEEKEND)
    }

    @Test
    fun `the same row always enriches identically`() {
        val first = DeterministicContextEnricher().enrich(record("2026-06-10"))
        val second = DeterministicContextEnricher().enrich(record("2026-06-10"))

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun `temperature agrees with the weather`() {
        val fixture =
            DatasetBaselineSource {
                javaClass
                    .getResourceAsStream(
                        "/fixtures/baseline.csv",
                    )!!
                    .reader()
            }.load()

        fixture.map { enricher.enrich(it) }.forEach { context ->
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
        val fixture =
            DatasetBaselineSource {
                javaClass
                    .getResourceAsStream(
                        "/fixtures/baseline.csv",
                    )!!
                    .reader()
            }.load()

        fixture.map { enricher.enrich(it) }.forEach { context ->
            if (context.eventType == MarketEvent.LOCAL_EVENT) {
                assertThat(context.eventNote).isNotNull()
            } else {
                assertThat(context.eventNote).isNull()
            }
        }
    }

    @Test
    fun `both halves of the committed fixture cover the Lesson key space`() {
        val fixture =
            DatasetBaselineSource {
                javaClass
                    .getResourceAsStream(
                        "/fixtures/baseline.csv",
                    )!!
                    .reader()
            }.load()

        DatasetSplit.entries.forEach { split ->
            val contexts = fixture.filter { it.split == split }.map { enricher.enrich(it) }
            assertThat(contexts.map { it.weather }.toSet())
                .describedAs("weather values in %s", split)
                .containsExactlyInAnyOrderElementsOf(Weather.entries)
            assertThat(contexts.map { it.eventType }.toSet())
                .describedAs("event values in %s", split)
                .containsExactlyInAnyOrderElementsOf(MarketEvent.entries)
        }
    }
}
