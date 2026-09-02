package club.podlodka.snowball.adapter

import club.podlodka.snowball.adapter.source.DatasetBaselineSource
import club.podlodka.snowball.adapter.source.FixtureRejection
import club.podlodka.snowball.domain.DatasetSplit
import club.podlodka.snowball.domain.StockLevel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import java.io.StringReader

class DatasetBaselineSourceTest {
    private val header = DatasetBaselineSource.REQUIRED_COLUMNS.joinToString(",")

    private fun source(vararg rows: String) =
        DatasetBaselineSource {
            StringReader((listOf(header) + rows).joinToString("\n"))
        }

    private fun row(
        date: String = "2026-06-03",
        split: String = "training",
        baseline: String = "100",
        stock: String = "150",
        price: String = "5.00",
        cost: String = "3.00",
        sku: String = "ICE500",
    ) = "ref-$sku-$date,$date,$split,$sku,Ice Cream 500ml,ice_cream,$price,$cost,$baseline,$stock"

    @Test
    fun `a well formed fixture is read`() {
        val records = source(row(), row(date = "2026-07-07", split = "benchmark")).load()

        assertThat(records).hasSize(2)
        assertThat(records[0].split).isEqualTo(DatasetSplit.TRAINING)
        assertThat(records[1].split).isEqualTo(DatasetSplit.BENCHMARK)
    }

    @Test
    fun `stock level is derived at twice the baseline`() {
        val records = source(row(baseline = "100", stock = "199"), row(baseline = "100", stock = "200")).load()

        assertThat(records[0].stockLevel).isEqualTo(StockLevel.NORMAL)
        assertThat(records[1].stockLevel).isEqualTo(StockLevel.HIGH)
    }

    @Test
    fun `a missing column is rejected`() {
        val truncated = DatasetBaselineSource { StringReader("source_reference,sku_id\nref,ICE500") }

        assertThatExceptionOfType(FixtureRejection::class.java)
            .isThrownBy { truncated.load() }
            .withMessageContaining("date")
    }

    @Test
    fun `an unknown split value is rejected`() {
        assertThatExceptionOfType(FixtureRejection::class.java)
            .isThrownBy { source(row(split = "validation")).load() }
            .withMessageContaining("training or benchmark")
    }

    @Test
    fun `a row without demand is rejected`() {
        assertThatExceptionOfType(FixtureRejection::class.java)
            .isThrownBy { source(row(baseline = "0")).load() }
            .withMessageContaining("baseline_sales")
    }

    @Test
    fun `a split that is not by time is rejected`() {
        assertThatExceptionOfType(FixtureRejection::class.java)
            .isThrownBy {
                source(
                    row(date = "2026-07-10", split = "training"),
                    row(date = "2026-07-01", split = "benchmark"),
                ).load()
            }.withMessageContaining("not by time")
    }

    @Test
    fun `the committed fixture loads and is split by time`() {
        val records =
            DatasetBaselineSource {
                javaClass
                    .getResourceAsStream(
                        "/fixtures/baseline.csv",
                    )!!
                    .reader()
            }.load()

        val training = records.filter { it.split == DatasetSplit.TRAINING }
        val benchmark = records.filter { it.split == DatasetSplit.BENCHMARK }
        assertThat(training).hasSize(250)
        assertThat(benchmark).hasSize(50)
        assertThat(training.maxOf { it.date }).isBefore(benchmark.minOf { it.date })
        assertThat(records.map { it.stockLevel }.toSet()).containsExactlyInAnyOrder(StockLevel.NORMAL, StockLevel.HIGH)
    }
}
