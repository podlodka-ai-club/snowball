package club.podlodka.snowball.adapter.simulator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.security.MessageDigest

/**
 * The one market shock a scenario gets, in `[0.98, 1.02]`.
 *
 * The discount is deliberately absent from the key. All four actions for a scenario must be
 * compared under the same shock, otherwise the Evaluator would be measuring noise as if it were
 * the effect of a discount, and every regret figure would be partly fiction.
 */
object DeterministicNoise {
    private val LOW = BigDecimal("0.98")
    private val SPAN = BigDecimal("0.04")
    private val MAX_UNSIGNED_64 = BigDecimal("18446744073709551615")

    fun forScenario(
        scenarioId: String,
        simulatorVersion: String,
    ): BigDecimal {
        val digest = MessageDigest.getInstance("SHA-256").digest("$simulatorVersion|$scenarioId".toByteArray())
        var unsigned = BigDecimal.ZERO
        for (index in 0 until 8) {
            unsigned = unsigned.multiply(BigDecimal(256)).add(BigDecimal(digest[index].toInt() and 0xff))
        }
        val unit = unsigned.divide(MAX_UNSIGNED_64, MathContext.DECIMAL64)
        return LOW.add(SPAN.multiply(unit)).setScale(6, RoundingMode.HALF_UP)
    }
}
