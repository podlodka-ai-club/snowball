package club.podlodka.snowball.domain

import java.math.BigDecimal

/**
 * What a lesson actually needs from a case: which actions earned what, and which won.
 *
 * Deliberately narrower than `PromotionCase`. Aggregation never looks at the scenario, and the
 * stored case does not carry the SKU or category as its own columns - they live on the related
 * SKU record. Reading evidence back as a full case would mean inventing those fields, so this
 * type says exactly what can honestly be reconstructed.
 */
data class CaseEvidence(
    val caseId: String,
    val profitByDiscount: Map<Discount, BigDecimal>,
    val bestDiscount: Discount,
) {
    init {
        require(caseId.isNotEmpty()) { "case_id must not be empty" }
        require(profitByDiscount.keys == Discount.entries.toSet()) {
            "evidence must carry a profit for every allowed action, had ${profitByDiscount.keys}"
        }
    }
}
