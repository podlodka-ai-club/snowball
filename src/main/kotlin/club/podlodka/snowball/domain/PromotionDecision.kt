package club.podlodka.snowball.domain

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonValue

/**
 * The closed set of promotion actions: `decision.discount` is `0`, `10`, `20`, or `30` percent.
 */
enum class Discount(
    @get:JsonValue val percent: Int,
) {
    NONE(0),
    TEN(10),
    TWENTY(20),
    THIRTY(30),
    ;

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromPercent(percent: Int): Discount =
            entries.firstOrNull { it.percent == percent }
                ?: throw IllegalArgumentException("Unsupported discount: $percent")
    }
}

/**
 * The `decision` object of `promotion-decision-v1.schema.json`, referenced by `$ref` from the
 * outcome contract and therefore reused by both events.
 */
data class PromotionDecision(
    @JsonProperty("discount", required = true)
    val discount: Discount,
)
