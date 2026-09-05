package club.podlodka.snowball.config

import club.podlodka.snowball.domain.DayType
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.MarketEvent
import club.podlodka.snowball.domain.Weather

/** One context bucket's pull on demand and on how well a promotion lands. */
data class ContextFactors(
    val demand: Double,
    val promo: Double,
) {
    companion object {
        val NEUTRAL = ContextFactors(1.00, 1.00)
    }
}

/**
 * The hidden market model of simulator v1, exactly as tabulated in `docs/market-simulator`.
 *
 * These numbers are the ground truth the agent is supposed to learn and must never reach it: they
 * stay behind `SimulationPort`, out of the outcome contract, out of xmemory, and out of any log
 * the agent reads. They are also frozen - `docs/market-simulator/README.md` requires a new
 * simulator version once training evidence exists, because changing them silently would
 * invalidate every lesson already learned.
 */
object SimulatorV1Config {
    const val VERSION: String = "v1"

    /** Demand multiplier a discount buys, before context affinity is applied. */
    private val BASE_DISCOUNT_LIFT: Map<String, Map<Discount, Double>> =
        mapOf(
            "ice_cream" to lift(0.00, 0.24, 0.60, 1.00),
            "beer" to lift(0.00, 0.22, 0.50, 0.82),
            "soft_drinks" to lift(0.00, 0.24, 0.58, 0.95),
            "chips" to lift(0.00, 0.28, 0.66, 1.05),
            "meat" to lift(0.00, 0.20, 0.48, 0.78),
            "yogurt" to lift(0.00, 0.24, 0.56, 0.92),
        )

    private val WEEKEND: Map<String, ContextFactors> =
        mapOf(
            "ice_cream" to ContextFactors(1.15, 1.15),
            "beer" to ContextFactors(1.15, 1.20),
            "soft_drinks" to ContextFactors(1.10, 1.10),
            "chips" to ContextFactors(1.10, 1.10),
            "meat" to ContextFactors(1.10, 1.05),
            "yogurt" to ContextFactors(1.00, 1.05),
        )

    private val HOT: Map<String, ContextFactors> =
        mapOf(
            "ice_cream" to ContextFactors(1.25, 1.35),
            "beer" to ContextFactors(1.10, 1.15),
            "soft_drinks" to ContextFactors(1.15, 1.25),
            "chips" to ContextFactors(1.00, 1.05),
            "meat" to ContextFactors(0.95, 0.95),
            "yogurt" to ContextFactors(1.00, 1.00),
        )

    private val RAIN: Map<String, ContextFactors> =
        mapOf(
            "ice_cream" to ContextFactors(0.85, 0.80),
            "beer" to ContextFactors(0.90, 0.90),
            "soft_drinks" to ContextFactors(0.95, 0.90),
            "chips" to ContextFactors(1.05, 1.10),
            "meat" to ContextFactors(1.00, 1.00),
            "yogurt" to ContextFactors(1.00, 1.05),
        )

    private val LOCAL_EVENT: Map<String, ContextFactors> =
        mapOf(
            "ice_cream" to ContextFactors(1.10, 1.10),
            "beer" to ContextFactors(1.20, 1.25),
            "soft_drinks" to ContextFactors(1.15, 1.15),
            "chips" to ContextFactors(1.15, 1.20),
            "meat" to ContextFactors(1.05, 1.05),
            "yogurt" to ContextFactors(1.00, 1.00),
        )

    val SUPPORTED_CATEGORIES: Set<String> = BASE_DISCOUNT_LIFT.keys

    fun baseDiscountLift(
        category: String,
        discount: Discount,
    ): Double = table(category).getValue(discount)

    fun dayFactors(
        category: String,
        dayType: DayType,
    ): ContextFactors =
        when (dayType) {
            DayType.WEEKDAY -> ContextFactors.NEUTRAL
            DayType.WEEKEND -> WEEKEND.getValue(requireSupported(category))
        }

    fun weatherFactors(
        category: String,
        weather: Weather,
    ): ContextFactors =
        when (weather) {
            Weather.NORMAL -> ContextFactors.NEUTRAL
            Weather.HOT -> HOT.getValue(requireSupported(category))
            Weather.RAIN -> RAIN.getValue(requireSupported(category))
        }

    fun eventFactors(
        category: String,
        eventType: MarketEvent,
    ): ContextFactors =
        when (eventType) {
            MarketEvent.NONE -> ContextFactors.NEUTRAL
            MarketEvent.LOCAL_EVENT -> LOCAL_EVENT.getValue(requireSupported(category))
        }

    private fun table(category: String): Map<Discount, Double> = BASE_DISCOUNT_LIFT.getValue(requireSupported(category))

    private fun requireSupported(category: String): String {
        require(category in BASE_DISCOUNT_LIFT) {
            "category '$category' has no simulator v1 coefficients; supported: ${SUPPORTED_CATEGORIES.sorted()}"
        }
        return category
    }

    private fun lift(
        none: Double,
        ten: Double,
        twenty: Double,
        thirty: Double,
    ): Map<Discount, Double> =
        mapOf(
            Discount.NONE to none,
            Discount.TEN to ten,
            Discount.TWENTY to twenty,
            Discount.THIRTY to thirty,
        )
}
