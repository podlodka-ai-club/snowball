package club.podlodka.snowball.config

import java.time.ZoneId

/**
 * The market identity injected into every scenario. The hackathon world is one fixed location, and
 * the baseline rows carry no market of their own, so it comes from configuration.
 */
data class MarketConfig(
    val storeId: String,
    val storeName: String,
    val timezone: ZoneId,
) {
    init {
        require(storeId.isNotEmpty()) { "store_id must not be empty" }
        require(storeName.isNotEmpty()) { "store_name must not be empty" }
    }

    companion object {
        val LONDON_CENTRAL =
            MarketConfig(
                storeId = "LONDON_CENTRAL",
                storeName = "London Central",
                timezone = ZoneId.of("Europe/London"),
            )
    }
}
