package club.podlodka.snowball.domain

import java.math.BigDecimal
import java.time.LocalDate

/** Which half of the experiment a fixture row belongs to. */
enum class DatasetSplit(
    val wire: String,
) {
    TRAINING("training"),
    BENCHMARK("benchmark"),
    ;

    companion object {
        fun fromWire(value: String): DatasetSplit =
            entries.firstOrNull { it.wire == value.trim() }
                ?: throw IllegalArgumentException("split must be training or benchmark, was '$value'")
    }
}

/**
 * One normalized baseline row, stripped of everything source-specific.
 *
 * `date` comes from the fixture rather than from the clock: `scenario_id` is built from it, so a
 * date chosen at generation time would make the same source fact produce a different identity on
 * every run.
 */
data class BaselineRecord(
    val sourceReference: String,
    val date: LocalDate,
    val split: DatasetSplit,
    val skuId: String,
    val skuName: String,
    val category: String,
    val price: BigDecimal,
    val cost: BigDecimal,
    val baselineSales: Int,
    val stock: Int,
) {
    init {
        require(sourceReference.isNotEmpty()) { "source_reference must not be empty" }
        require(skuId.isNotEmpty()) { "sku_id must not be empty" }
        require(skuName.isNotEmpty()) { "sku_name must not be empty" }
        require(category.isNotEmpty()) { "category must not be empty" }
        require(price > BigDecimal.ZERO) { "price must be greater than 0, was $price" }
        require(cost >= BigDecimal.ZERO) { "cost must not be negative, was $cost" }
        require(baselineSales > 0) {
            "baseline_sales must be positive: a row with no demand yields no meaningful stock level"
        }
        require(stock >= 0) { "stock must not be negative, was $stock" }
    }

    /**
     * `high` once stock reaches twice the baseline demand. The preparation guide derives stock at
     * roughly 1.5x baseline for normal and 2.5x for high, so the midpoint separates them and
     * survives the tuning it allows.
     */
    val stockLevel: StockLevel
        get() = if (stock.toLong() >= 2L * baselineSales) StockLevel.HIGH else StockLevel.NORMAL
}
