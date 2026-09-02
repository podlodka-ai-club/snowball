package club.podlodka.snowball.adapter.source

import club.podlodka.snowball.domain.BaselineRecord
import club.podlodka.snowball.domain.DatasetSplit
import club.podlodka.snowball.domain.SourceRejection
import club.podlodka.snowball.port.BaselineLoad
import club.podlodka.snowball.port.BaselineSource
import java.io.Reader
import java.math.BigDecimal
import java.time.LocalDate

/** The fixture itself is unusable - not one row, but the file. */
class FixtureRejection(
    message: String,
) : IllegalArgumentException(message)

/**
 * Reads the normalized fixture produced by `tools/prepare_dunnhumby.py`.
 *
 * The runtime never touches the raw public dataset - only this small committed CSV, whose columns
 * are the ones the preparation guide recommends plus `date` and `split`.
 *
 * A malformed row is reported and skipped rather than aborting the batch; only a defect in the
 * file as a whole - a missing column, or a split that is not by time - stops the read, because
 * neither can be attributed to one row.
 */
class DatasetBaselineSource(
    private val sourceType: String = "dataset",
    private val open: () -> Reader,
) : BaselineSource {
    override fun load(): BaselineLoad {
        open().use { reader ->
            val lines = reader.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) throw FixtureRejection("fixture is empty")
            val header = split(lines.first())
            REQUIRED_COLUMNS.forEach { name ->
                if (name !in header) throw FixtureRejection("fixture is missing the '$name' column")
            }
            val records = mutableListOf<BaselineRecord>()
            val rejections = mutableListOf<SourceRejection>()
            lines.drop(1).forEachIndexed { index, line ->
                val values = split(line)
                try {
                    records += parse(header, values, index + 2)
                } catch (failure: RuntimeException) {
                    rejections +=
                        SourceRejection(
                            sourceType = sourceType,
                            sourceReference = referenceOf(header, values),
                            scenarioId = null,
                            reason = "line ${index + 2}: ${failure.message}",
                        )
                }
            }
            requireTimeOrderedSplit(records)
            return BaselineLoad(records, rejections)
        }
    }

    private fun split(line: String): List<String> = line.split(',').map { it.trim() }

    private fun referenceOf(
        header: List<String>,
        values: List<String>,
    ): String? = header.indexOf("source_reference").takeIf { it >= 0 && it < values.size }?.let { values[it] }

    private fun parse(
        header: List<String>,
        values: List<String>,
        lineNumber: Int,
    ): BaselineRecord {
        if (values.size != header.size) {
            throw IllegalArgumentException("has ${values.size} fields, expected ${header.size}")
        }
        val row = header.zip(values).toMap()
        return BaselineRecord(
            sourceReference = row.getValue("source_reference"),
            date = LocalDate.parse(row.getValue("date")),
            split = DatasetSplit.fromWire(row.getValue("split")),
            skuId = row.getValue("sku_id"),
            skuName = row.getValue("sku_name"),
            category = row.getValue("category"),
            price = BigDecimal(row.getValue("price")),
            cost = BigDecimal(row.getValue("cost")),
            baselineSales = row.getValue("baseline_sales").toInt(),
            stock = row.getValue("stock").toInt(),
        )
    }

    /**
     * A split that is not by time lets the benchmark measure memorised homework. This is a property
     * of the file rather than of a row, so it fails the whole read.
     */
    private fun requireTimeOrderedSplit(records: List<BaselineRecord>) {
        val training = records.filter { it.split == DatasetSplit.TRAINING }
        val benchmark = records.filter { it.split == DatasetSplit.BENCHMARK }
        if (training.isEmpty() || benchmark.isEmpty()) return
        val lastTraining = training.maxOf { it.date }
        val firstBenchmark = benchmark.minOf { it.date }
        if (!lastTraining.isBefore(firstBenchmark)) {
            throw FixtureRejection(
                "split is not by time: training runs to $lastTraining but benchmark starts $firstBenchmark",
            )
        }
    }

    companion object {
        val REQUIRED_COLUMNS =
            listOf(
                "source_reference",
                "date",
                "split",
                "sku_id",
                "sku_name",
                "category",
                "price",
                "cost",
                "baseline_sales",
                "stock",
            )
    }
}
