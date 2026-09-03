package club.podlodka.snowball.domain

/** Which family of scenarios a lesson generalises over. */
enum class LessonScope(
    val prefix: String,
) {
    SKU("sku"),
    CATEGORY("category"),
}

/**
 * The identity of a lesson bucket.
 *
 * Deliberately narrow: scope, day type, weather and stock level, with `store:any` and `event:any`
 * fixed for v1. The hackathon has one store, so store specificity would be fake, and letting the
 * key grow would let prose invent a new bucket every time the same evidence is worded differently
 * - which is how memory fills up with singletons that never accumulate enough evidence to mean
 * anything.
 */
data class LessonKey(
    val scope: LessonScope,
    val scopeValue: String,
    val dayType: DayType,
    val weather: Weather,
    val stockLevel: StockLevel,
) {
    init {
        require(scopeValue.isNotEmpty()) { "lesson scope value must not be empty" }
    }

    /** The wire form, exactly as `docs/xmemory/README.md` tabulates it. */
    val wire: String
        get() =
            "${scope.prefix}:$scopeValue|store:any|${dayType.wire}|${weather.wire}|event:any|stock:${stockLevel.wire}"

    override fun toString(): String = wire

    companion object {
        /**
         * The two buckets one case contributes to: its exact SKU, and its category.
         *
         * Two is the whole policy - enough to show both exact-SKU learning and transfer to similar
         * products, without a case seeding a dozen buckets that each hold one observation.
         */
        fun bucketsFor(scenario: PromotionScenario): List<LessonKey> =
            listOf(
                LessonKey(LessonScope.SKU, scenario.skuId, scenario.dayType, scenario.weather, scenario.stockLevel),
                LessonKey(
                    LessonScope.CATEGORY,
                    scenario.category,
                    scenario.dayType,
                    scenario.weather,
                    scenario.stockLevel,
                ),
            )
    }
}
