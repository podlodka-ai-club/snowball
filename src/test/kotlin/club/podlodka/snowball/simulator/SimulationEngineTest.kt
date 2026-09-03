package club.podlodka.snowball.simulator

import club.podlodka.snowball.adapter.simulator.DeterministicNoise
import club.podlodka.snowball.adapter.simulator.SimulationEngine
import club.podlodka.snowball.domain.CommittedDocs
import club.podlodka.snowball.domain.ContractJson
import club.podlodka.snowball.domain.DayType
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.MarketEvent
import club.podlodka.snowball.domain.PromotionScenario
import club.podlodka.snowball.domain.PromotionScenarioEvent
import club.podlodka.snowball.domain.StockLevel
import club.podlodka.snowball.domain.Weather
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SimulationEngineTest {
    private val engine = SimulationEngine()

    private fun scenario(): PromotionScenario =
        ContractJson.mapper
            .readValue<PromotionScenarioEvent>(CommittedDocs.read(CommittedDocs.SCENARIO_EXAMPLE))
            .scenario

    @Test
    fun `the same inputs always produce the same ground truth`() {
        val first = engine.simulate("SCN-A", scenario(), Discount.TWENTY)
        val second = SimulationEngine().simulate("SCN-A", scenario(), Discount.TWENTY)

        assertThat(second).isEqualTo(first)
    }

    @Test
    fun `all four actions share one market shock`() {
        // The whole point of leaving discount out of the noise key: if each action got its own
        // shock, the Evaluator would read noise as the effect of the discount and every regret
        // number would be partly fiction.
        val noise = DeterministicNoise.forScenario("SCN-A", "v1")

        assertThat(noise).isEqualByComparingTo(DeterministicNoise.forScenario("SCN-A", "v1"))
        assertThat(noise).isBetween(BigDecimal("0.98"), BigDecimal("1.02"))
        assertThat(noise.scale()).isEqualTo(6)
    }

    @Test
    fun `different scenarios get different shocks`() {
        assertThat(DeterministicNoise.forScenario("SCN-A", "v1"))
            .isNotEqualByComparingTo(DeterministicNoise.forScenario("SCN-B", "v1"))
    }

    @Test
    fun `a deeper discount sells more units until stock runs out`() {
        val plentiful = scenario().copy(stock = 100_000)

        val units = Discount.entries.map { engine.simulate("SCN-A", plentiful, it).unitsSold }

        assertThat(units).isSorted()
        assertThat(units.last()).isGreaterThan(units.first())
    }

    @Test
    fun `stock caps what can be sold`() {
        val scarce = scenario().copy(stock = 5)

        Discount.entries.forEach { discount ->
            assertThat(engine.simulate("SCN-A", scarce, discount).unitsSold).isLessThanOrEqualTo(5)
        }
    }

    @Test
    fun `context changes which action is best, not just how much sells`() {
        // If weather only scaled demand it would cancel out across actions and there would be
        // nothing contextual for memory to learn. Hot weather makes ice cream promotions land
        // harder, so it must move units at a discount more than it moves them at full price.
        val base = scenario().copy(stock = 100_000, weather = Weather.NORMAL)
        val hot = base.copy(weather = Weather.HOT)

        val normalGain =
            engine.simulate("SCN-A", base, Discount.TWENTY).unitsSold -
                engine.simulate("SCN-A", base, Discount.NONE).unitsSold
        val hotGain =
            engine.simulate("SCN-A", hot, Discount.TWENTY).unitsSold -
                engine.simulate("SCN-A", hot, Discount.NONE).unitsSold

        assertThat(hotGain).isGreaterThan(normalGain)
    }

    @Test
    fun `money is rounded to two places and may go negative below cost`() {
        val thinMargin = scenario().copy(price = BigDecimal("5.00"), cost = BigDecimal("4.50"), stock = 100_000)

        val outcome = engine.simulate("SCN-A", thinMargin, Discount.THIRTY)

        assertThat(outcome.grossProfit.scale()).isEqualTo(2)
        assertThat(outcome.grossProfit).isLessThan(BigDecimal.ZERO)
    }

    @Test
    fun `an unsupported category is refused rather than guessed`() {
        val unknown = scenario().copy(category = "stationery")

        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { engine.simulate("SCN-A", unknown, Discount.TEN) }
            .withMessageContaining("stationery")
    }

    @Test
    fun `zero discount is unaffected by promotion affinity`() {
        // base lift is 0 at 0%, so context affinity has nothing to multiply - the weekend and the
        // weather may move demand, but they must not invent a promotion effect where none exists.
        val weekday = scenario().copy(stock = 100_000, dayType = DayType.WEEKDAY, eventType = MarketEvent.NONE)
        val weekend = weekday.copy(dayType = DayType.WEEKEND)

        val weekdayUnits = engine.simulate("SCN-A", weekday, Discount.NONE).unitsSold
        val weekendUnits = engine.simulate("SCN-A", weekend, Discount.NONE).unitsSold

        assertThat(weekendUnits).isGreaterThan(weekdayUnits)
    }

    @Test
    fun `stock level is not an input to the arithmetic`() {
        // stock_level is a label for the agent and for Lesson keys; the physics uses the number.
        val normal = scenario().copy(stockLevel = StockLevel.NORMAL)
        val high = normal.copy(stockLevel = StockLevel.HIGH)

        assertThat(engine.simulate("SCN-A", high, Discount.TEN))
            .isEqualTo(engine.simulate("SCN-A", normal, Discount.TEN))
    }
}
