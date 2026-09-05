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
        DatasetBaselineSource { StringReader((listOf(header) + rows).joinToString("\n")) }

    private fun row(
        date: String = "2026-06-03",
        split: String = "training",
        baseline: String = "100",
        stock: String = "150",
        price: String = "5.00",
        cost: String = "3.00",
        sku: String = "ICE500",
    ) = "ref-$sku-$date,$date,$split,$sku,Ice Cream 500ml,ice_cream,$price,$cost,$baseline,$stock"

    private fun committed() =
        DatasetBaselineSource { javaClass.getResourceAsStream("/fixtures/baseline.csv")!!.reader() }

    @Test
    fun `a well formed fixture is read`() {
        val load = source(row(), row(date = "2026-07-07", split = "benchmark")).load()

        assertThat(load.records).hasSize(2)
        assertThat(load.rejections).isEmpty()
        assertThat(load.records[0].split).isEqualTo(DatasetSplit.TRAINING)
        assertThat(load.records[1].split).isEqualTo(DatasetSplit.BENCHMARK)
    }

    @Test
    fun `stock level is derived at twice the baseline`() {
        val load = source(row(baseline = "100", stock = "199"), row(baseline = "100", stock = "200")).load()

        assertThat(load.records[0].stockLevel).isEqualTo(StockLevel.NORMAL)
        assertThat(load.records[1].stockLevel).isEqualTo(StockLevel.HIGH)
    }

    @Test
    fun `a missing column fails the whole file`() {
        val truncated = DatasetBaselineSource { StringReader("source_reference,sku_id\nref,ICE500") }

        assertThatExceptionOfType(FixtureRejection::class.java)
            .isThrownBy { truncated.load() }
            .withMessageContaining("date")
    }

    @Test
    fun `a bad row is reported but the rest of the batch survives`() {
        val load =
            source(
                row(sku = "GOOD1"),
                row(sku = "BAD1", split = "validation"),
                row(sku = "GOOD2"),
            ).load()

        assertThat(load.records.map { it.skuId }).containsExactly("GOOD1", "GOOD2")
        assertThat(load.rejections).hasSize(1)
        assertThat(load.rejections.single().reason).contains("training or benchmark")
        assertThat(load.rejections.single().sourceReference).contains("BAD1")
    }

    @Test
    fun `a row without demand is reported and skipped`() {
        val load = source(row(baseline = "0")).load()

        assertThat(load.records).isEmpty()
        assertThat(load.rejections.single().reason).contains("baseline_sales")
    }

    @Test
    fun `a non-positive price is reported and skipped`() {
        val load = source(row(price = "0.00"), row(price = "-1.00", sku = "NEG1")).load()

        assertThat(load.records).isEmpty()
        assertThat(load.rejections).hasSize(2)
        assertThat(load.rejections).allMatch { it.reason.contains("price") }
    }

    @Test
    fun `a negative cost is reported and skipped`() {
        val load = source(row(cost = "-0.01")).load()

        assertThat(load.records).isEmpty()
        assertThat(load.rejections.single().reason).contains("cost")
    }

    @Test
    fun `a structured rejection carries the source identity`() {
        val rejection = source(row(baseline = "0", sku = "ICE500")).load().rejections.single()

        assertThat(rejection.sourceType).isEqualTo("dataset")
        assertThat(rejection.sourceReference).isEqualTo("ref-ICE500-2026-06-03")
        assertThat(rejection.toString()).contains("source_type=dataset", "source_reference=ref-ICE500")
    }

    @Test
    fun `a split that is not by time fails the whole file`() {
        assertThatExceptionOfType(FixtureRejection::class.java)
            .isThrownBy {
                source(
                    row(date = "2026-07-10", split = "training"),
                    row(date = "2026-07-01", split = "benchmark"),
                ).load()
            }.withMessageContaining("not by time")
    }

    @Test
    fun `a training and a benchmark row on the same day fail the file`() {
        // The requirement is strictly later, not "not earlier": sharing a boundary day would let a
        // benchmark scenario sit in the same day the agent trained on.
        assertThatExceptionOfType(FixtureRejection::class.java)
            .isThrownBy {
                source(
                    row(date = "2026-07-01", split = "training"),
                    row(date = "2026-07-01", split = "benchmark", sku = "BEER6"),
                ).load()
            }.withMessageContaining("not by time")
    }

    @Test
    fun `an out-of-order benchmark row condemns the file even when it is otherwise invalid`() {
        // The row is dropped as a bad row, but its date still proves the split is wrong. Judging
        // order only on surviving rows would let a broken fixture look well ordered.
        assertThatExceptionOfType(FixtureRejection::class.java)
            .isThrownBy {
                source(
                    row(date = "2026-07-10", split = "training"),
                    row(date = "2026-07-01", split = "benchmark", price = "0.00", sku = "BAD1"),
                ).load()
            }.withMessageContaining("not by time")
    }

    @Test
    fun `a header without rows fails the file`() {
        assertThatExceptionOfType(FixtureRejection::class.java)
            .isThrownBy { source().load() }
            .withMessageContaining("no rows")
    }

    @Test
    fun `the committed fixture loads clean and is split by time`() {
        val load = committed().load()

        assertThat(load.rejections).isEmpty()
        val training = load.records.filter { it.split == DatasetSplit.TRAINING }
        val benchmark = load.records.filter { it.split == DatasetSplit.BENCHMARK }
        assertThat(training).hasSize(250)
        assertThat(benchmark).hasSize(50)
        assertThat(training.maxOf { it.date }).isBefore(benchmark.minOf { it.date })
        assertThat(load.records.map { it.stockLevel }.toSet())
            .containsExactlyInAnyOrder(StockLevel.NORMAL, StockLevel.HIGH)
    }
}
