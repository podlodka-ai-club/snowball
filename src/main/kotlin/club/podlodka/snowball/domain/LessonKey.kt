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
 * A condition may be absent, which is what `any` means on the wire and an empty column in the
 * memory schema: the lesson applies whatever that condition happens to be. Store and event are
 * always absent in v1 - the hackathon has one store, so store specificity would be fake.
 *
 * Absent conditions exist because the benchmark showed the limit was coverage, not lesson quality.
 * A key naming every condition is precise and often missing: on the held-out set the strict key
 * answered 47 scenarios of 50, and the three it missed carried more loss than all 47 together.
 * Dropping a condition trades precision for reach, so the buckets are ordered and consulted
 * strictest first rather than replaced by looser ones.
 */
data class LessonKey(
    val scope: LessonScope,
    val scopeValue: String,
    val dayType: DayType?,
    val weather: Weather?,
    val stockLevel: StockLevel?,
) {
    init {
        require(scopeValue.isNotEmpty()) { "lesson scope value must not be empty" }
    }

    /** The wire form, exactly as `docs/xmemory/README.md` tabulates it. */
    val wire: String
        get() =
            "${scope.prefix}:$scopeValue|store:any|${dayType?.wire ?: ANY}|${weather?.wire ?: ANY}" +
                "|event:any|stock:${stockLevel?.wire ?: ANY}"

    /** How many conditions this key pins down; a tie-break that prefers the more specific bucket. */
    val specificity: Int get() = listOfNotNull(dayType, weather, stockLevel).size

    override fun toString(): String = wire

    companion object {
        const val ANY = "any"

        /**
         * Every bucket one case contributes to, strictest first.
         *
         * Three levels per scope rather than one. Which conditions to drop, and in what order, was
         * measured on the held-out set rather than guessed: weather is the weakest signal - keyed
         * on weather alone the advice is worse than advice keyed on nothing at all - so it goes
         * first, then the rest. A single looser key would be worse than the strict one; a cascade
         * is better than either, because it only generalises where the precise answer is missing.
         */
        fun bucketsFor(scenario: PromotionScenario): List<LessonKey> =
            LessonScope.entries.flatMap { scope ->
                val value = if (scope == LessonScope.SKU) scenario.skuId else scenario.category
                listOf(
                    LessonKey(scope, value, scenario.dayType, scenario.weather, scenario.stockLevel),
                    LessonKey(scope, value, scenario.dayType, null, scenario.stockLevel),
                    LessonKey(scope, value, null, null, null),
                )
            }
    }
}
