package club.podlodka.snowball.learning

import club.podlodka.snowball.domain.DayType
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.Lesson
import club.podlodka.snowball.domain.LessonKey
import club.podlodka.snowball.domain.LessonScope
import club.podlodka.snowball.domain.PromotionCase
import club.podlodka.snowball.domain.PromotionScenario
import club.podlodka.snowball.domain.SimulatorVersion
import club.podlodka.snowball.domain.StockLevel
import club.podlodka.snowball.domain.Weather
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class LessonTest {
    private val key =
        LessonKey(LessonScope.SKU, "ICE500", DayType.WEEKEND, Weather.HOT, StockLevel.HIGH)

    private fun scenario() =
        PromotionScenario(
            date = LocalDate.parse("2026-07-18"),
            storeId = "LONDON_CENTRAL",
            skuId = "ICE500",
            category = "ice_cream",
            price = BigDecimal("5.00"),
            cost = BigDecimal("3.00"),
            stock = 320,
            baselineSales = 100,
            stockLevel = StockLevel.HIGH,
            dayType = DayType.WEEKEND,
            weather = Weather.HOT,
            eventType = club.podlodka.snowball.domain.MarketEvent.NONE,
        )

    /** A case with the four profits stated outright, so the aggregation is tested and not the simulator. */
    private fun case(
        id: String,
        profits: Map<Discount, String>,
        chosen: Discount = Discount.NONE,
    ): PromotionCase {
        val byDiscount = profits.mapValues { BigDecimal(it.value) }
        val best =
            Discount.entries
                .sortedWith(compareByDescending<Discount> { byDiscount.getValue(it) }.thenBy { it.percent })
                .first()
        return PromotionCase(
            caseId = id,
            scenarioId = "SCN-$id",
            simulatorVersion = SimulatorVersion.V1,
            scenario = scenario(),
            chosenDiscount = chosen,
            chosenUnitsSold = 100,
            chosenGrossProfit = byDiscount.getValue(chosen),
            profitByDiscount = byDiscount,
            bestDiscount = best,
        )
    }

    private fun profits(
        none: String,
        ten: String,
        twenty: String,
        thirty: String,
    ) = mapOf(
        Discount.NONE to none,
        Discount.TEN to ten,
        Discount.TWENTY to twenty,
        Discount.THIRTY to thirty,
    )

    @Test
    fun `the recommendation is the action with the best aggregate profit`() {
        val lesson =
            Lesson.from(
                key,
                listOf(
                    case("A", profits("100", "150", "120", "90")),
                    case("B", profits("100", "160", "130", "80")),
                ),
            )

        assertThat(lesson.recommendedDiscount).isEqualTo(Discount.TEN)
        assertThat(lesson.evidenceCount).isEqualTo(2)
    }

    @Test
    fun `an exact tie prefers the lower discount`() {
        // Giving away less money for the same return is the better advice, and a deterministic
        // tie-break keeps the lesson stable instead of flipping on map ordering.
        val lesson = Lesson.from(key, listOf(case("A", profits("100", "100", "50", "40"))))

        assertThat(lesson.recommendedDiscount).isEqualTo(Discount.NONE)
    }

    @Test
    fun `new evidence can overturn the recommendation`() {
        val first = Lesson.from(key, listOf(case("A", profits("100", "150", "120", "90"))))
        assertThat(first.recommendedDiscount).isEqualTo(Discount.TEN)

        val overturned =
            Lesson.from(
                key,
                listOf(
                    case("A", profits("100", "150", "120", "90")),
                    case("B", profits("100", "10", "400", "90")),
                ),
            )

        assertThat(overturned.recommendedDiscount).isEqualTo(Discount.TWENTY)
        assertThat(overturned.key).isEqualTo(first.key)
    }

    @Test
    fun `advantage is measured against the best alternative, not the worst`() {
        val lesson = Lesson.from(key, listOf(case("A", profits("100", "200", "150", "50"))))

        // recommended 200, best alternative 150 -> (200-150)/150*100 = 33.33
        assertThat(lesson.avgProfitAdvantagePct).isEqualByComparingTo(BigDecimal("33.33"))
    }

    @Test
    fun `confidence is dominated by how much evidence there is`() {
        val one = Lesson.from(key, listOf(case("A", profits("100", "200", "150", "50"))))
        val five =
            Lesson.from(
                key,
                (1..5).map { case("C$it", profits("100", "200", "150", "50")) },
            )

        assertThat(five.confidence).isGreaterThan(one.confidence)
        assertThat(five.confidence).isLessThanOrEqualTo(BigDecimal.ONE)
        assertThat(one.confidence).isGreaterThan(BigDecimal.ZERO)
    }

    @Test
    fun `disagreeing evidence lowers confidence`() {
        val agreeing = (1..4).map { case("A$it", profits("100", "200", "150", "50"), chosen = Discount.TEN) }
        val mixed =
            listOf(
                case("M1", profits("100", "200", "150", "50")),
                case("M2", profits("300", "200", "150", "50")),
                case("M3", profits("300", "200", "150", "50")),
                case("M4", profits("100", "200", "150", "50")),
            )

        assertThat(Lesson.from(key, mixed).confidence)
            .isLessThan(Lesson.from(key, agreeing).confidence)
    }

    @Test
    fun `duplicate cases do not inflate the evidence count`() {
        val one = case("A", profits("100", "200", "150", "50"))

        val lesson = Lesson.from(key, listOf(one, one, one))

        assertThat(lesson.evidenceCount).isEqualTo(1)
    }

    @Test
    fun `the rationale states the facts it was computed from`() {
        val lesson = Lesson.from(key, listOf(case("A", profits("100", "200", "150", "50"))))

        assertThat(lesson.rationale)
            .contains("sku:ICE500", "hot", "weekend", "high stock", "10%", "1 evaluated case", "33.33%")
    }

    @Test
    fun `the key is the documented wire form`() {
        assertThat(key.wire)
            .isEqualTo("sku:ICE500|store:any|weekend|hot|event:any|stock:high")
    }
}
