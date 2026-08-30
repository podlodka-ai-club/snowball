package club.podlodka.snowball.domain

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder
import com.fasterxml.jackson.annotation.JsonValue
import java.time.LocalDate

/**
 * `scenario.stock_level` of `promotion-scenario-v1.schema.json`.
 */
enum class StockLevel(
    @get:JsonValue val wire: String,
) {
    NORMAL("normal"),
    HIGH("high"),
}

/**
 * `scenario.day_type` of `promotion-scenario-v1.schema.json`.
 */
enum class DayType(
    @get:JsonValue val wire: String,
) {
    WEEKDAY("weekday"),
    WEEKEND("weekend"),
}

/**
 * `scenario.weather` of `promotion-scenario-v1.schema.json`.
 */
enum class Weather(
    @get:JsonValue val wire: String,
) {
    NORMAL("normal"),
    HOT("hot"),
    RAIN("rain"),
}

/**
 * `scenario.event_type` of `promotion-scenario-v1.schema.json`, named after what it describes so
 * that it does not collide with the `event_type` envelope constant of the surrounding event.
 */
enum class MarketEvent(
    @get:JsonValue val wire: String,
) {
    NONE("none"),
    LOCAL_EVENT("local_event"),
}

/**
 * The `source` object of `promotion-scenario-v1.schema.json`.
 */
@JsonPropertyOrder("type", "reference")
data class ScenarioSource(
    @JsonProperty("type", required = true)
    val type: String,
    @JsonProperty("reference", required = true)
    val reference: String,
) {
    init {
        require(type.isNotEmpty()) { "source.type must not be empty" }
        require(reference.isNotEmpty()) { "source.reference must not be empty" }
    }
}

/**
 * The `scenario` object of `promotion-scenario-v1.schema.json`.
 *
 * The decision and outcome contracts reference this same object by `$ref`, so all three events
 * reuse this single type instead of redeclaring its fields.
 */
@JsonPropertyOrder(
    "date",
    "store_id",
    "store_name",
    "sku_id",
    "sku_name",
    "category",
    "price",
    "cost",
    "stock",
    "baseline_sales",
    "stock_level",
    "day_type",
    "weather",
    "temperature_c",
    "event_type",
    "event_note",
)
data class PromotionScenario(
    @JsonProperty("date", required = true)
    val date: LocalDate,
    @JsonProperty("store_id", required = true)
    val storeId: String,
    @JsonProperty("sku_id", required = true)
    val skuId: String,
    @JsonProperty("category", required = true)
    val category: String,
    @JsonProperty("price", required = true)
    val price: Double,
    @JsonProperty("cost", required = true)
    val cost: Double,
    @JsonProperty("stock", required = true)
    val stock: Int,
    @JsonProperty("baseline_sales", required = true)
    val baselineSales: Int,
    @JsonProperty("stock_level", required = true)
    val stockLevel: StockLevel,
    @JsonProperty("day_type", required = true)
    val dayType: DayType,
    @JsonProperty("weather", required = true)
    val weather: Weather,
    @JsonProperty("event_type", required = true)
    val eventType: MarketEvent,
    @JsonProperty("store_name")
    val storeName: String? = null,
    @JsonProperty("sku_name")
    val skuName: String? = null,
    @JsonProperty("temperature_c")
    val temperatureC: Double? = null,
    @JsonProperty("event_note")
    val eventNote: String? = null,
) {
    init {
        require(storeId.isNotEmpty()) { "scenario.store_id must not be empty" }
        require(skuId.isNotEmpty()) { "scenario.sku_id must not be empty" }
        require(category.isNotEmpty()) { "scenario.category must not be empty" }
        require(price > 0) { "scenario.price must be greater than 0, was $price" }
        require(cost >= 0) { "scenario.cost must not be negative, was $cost" }
        require(stock >= 0) { "scenario.stock must not be negative, was $stock" }
        require(baselineSales >= 0) { "scenario.baseline_sales must not be negative, was $baselineSales" }
        require(storeName == null || storeName.isNotEmpty()) { "scenario.store_name must not be empty" }
        require(skuName == null || skuName.isNotEmpty()) { "scenario.sku_name must not be empty" }
        require(eventNote == null || eventNote.isNotEmpty()) { "scenario.event_note must not be empty" }
    }
}
