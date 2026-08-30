package club.podlodka.snowball.domain

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyOrder

/**
 * The `outcome` object of `promotion-outcome-v1.schema.json`.
 *
 * This is the whole business result the Market Simulator is allowed to publish: no coefficients,
 * no noise factor, no counterfactuals, no oracle-best action.
 */
@JsonPropertyOrder("units_sold", "gross_profit")
data class PromotionOutcome(
    @JsonProperty("units_sold", required = true)
    val unitsSold: Int,
    @JsonProperty("gross_profit", required = true)
    val grossProfit: Double,
) {
    init {
        require(unitsSold >= 0) { "outcome.units_sold must not be negative, was $unitsSold" }
    }
}
