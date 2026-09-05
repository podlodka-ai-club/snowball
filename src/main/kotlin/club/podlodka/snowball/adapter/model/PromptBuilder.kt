package club.podlodka.snowball.adapter.model

import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.Lesson
import club.podlodka.snowball.domain.PromotionScenario

/**
 * Builds the one prompt both benchmark arms use.
 *
 * The clean-memory and trained-memory runs must differ only in the lessons block - if the wording
 * changed with memory, the measured delta would partly be the delta between two prompts, and the
 * experiment would prove nothing. An empty memory therefore renders as "none." inside the same
 * template rather than as a different instruction.
 *
 * Nothing here may carry simulator internals: no coefficients, no noise, no counterfactual
 * profits, no oracle. The agent is meant to learn the market, not to read the answer key.
 */
object PromptBuilder {
    const val SYSTEM: String =
        "You choose one promotion discount for one product for one day. " +
            "Allowed actions: 0, 10, 20, 30. " +
            "Answer with JSON only: {\"discount\": <one of 0,10,20,30>}. No other text."

    fun user(
        scenario: PromotionScenario,
        lessons: List<Lesson>,
    ): String =
        buildString {
            append("Scenario:\n")
            append("  sku: ${scenario.skuId}")
            scenario.skuName?.let { append(" ($it)") }
            append(", category: ${scenario.category}\n")
            append("  date: ${scenario.date} (${scenario.dayType.wire})\n")
            append("  weather: ${scenario.weather.wire}")
            scenario.temperatureC?.let { append(", ${it}C") }
            append("\n  price: ${scenario.price}, cost: ${scenario.cost}\n")
            append(
                "  baseline_sales: ${scenario.baselineSales}, stock: ${scenario.stock} (${scenario.stockLevel.wire})\n",
            )
            append("  local event: ${scenario.eventType.wire}\n\n")
            append("Lessons from memory:")
            if (lessons.isEmpty()) {
                append(" none.\n")
            } else {
                append("\n")
                lessons.forEachIndexed { index, lesson ->
                    append("  ${index + 1}. ${lesson.rationale} confidence ${lesson.confidence}\n")
                }
            }
            append("\nAllowed actions: ${Discount.entries.joinToString(", ") { "${it.percent}%" }}\n")
            append("Choose the discount.")
        }
}
