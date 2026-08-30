package club.podlodka.snowball.port

import club.podlodka.snowball.adapter.inprocess.FixedSimulation
import club.podlodka.snowball.adapter.inprocess.RecordingOutcomeSink
import club.podlodka.snowball.adapter.inprocess.RecordingScenarioPublisher
import club.podlodka.snowball.domain.CommittedDocs
import club.podlodka.snowball.domain.ContractJson
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.PromotionOutcome
import club.podlodka.snowball.domain.PromotionOutcomeEvent
import club.podlodka.snowball.domain.PromotionScenarioEvent
import com.fasterxml.jackson.module.kotlin.readValue
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The ports must be usable in process today and must stay transport-neutral, so that whichever
 * transport the team settles on later can be added without touching these interfaces.
 */
class PortsTest {
    private val mapper = ContractJson.mapper

    private fun scenarioEvent(): PromotionScenarioEvent =
        mapper.readValue(CommittedDocs.read(CommittedDocs.SCENARIO_EXAMPLE))

    private fun outcomeEvent(): PromotionOutcomeEvent =
        mapper.readValue(CommittedDocs.read(CommittedDocs.OUTCOME_EXAMPLE))

    @Test
    fun `simulation port returns only the committed outcome payload`() {
        val expected = PromotionOutcome(unitsSold = 320, grossProfit = 320.0)
        val simulation = FixedSimulation(expected)
        val scenario = scenarioEvent().scenario

        val result = simulation.simulate(scenario, Discount.TWENTY)

        assertThat(result).isEqualTo(expected)
        assertThat(simulation.calls).containsExactly(scenario to Discount.TWENTY)

        // The outcome payload has exactly the two properties the schema allows, so there is nowhere
        // for coefficients, noise, counterfactuals, or an oracle answer to leak through this port.
        assertThat(mapper.readTree(mapper.writeValueAsString(result)).fieldNames())
            .toIterable()
            .containsExactlyInAnyOrder("units_sold", "gross_profit")
    }

    @Test
    fun `outcome sink records what it accepts`() {
        val sink = RecordingOutcomeSink()
        val outcome = outcomeEvent()

        sink.accept(outcome)

        assertThat(sink.received).containsExactly(outcome)
    }

    @Test
    fun `scenario publisher records what it publishes`() {
        val publisher = RecordingScenarioPublisher()
        val scenario = scenarioEvent()

        publisher.publish(scenario)

        assertThat(publisher.published).containsExactly(scenario)
    }

    @Test
    fun `ports can be replaced by test doubles`() {
        val sink = mockk<OutcomeSink>(relaxed = true)
        val outcome = outcomeEvent()

        sink.accept(outcome)

        verify(exactly = 1) { sink.accept(outcome) }
    }

    @Test
    fun `port signatures name only contract types`() {
        val ports = listOf(SimulationPort::class.java, OutcomeSink::class.java, ScenarioPublisher::class.java)

        ports.forEach { port ->
            port.declaredMethods.forEach { method ->
                val types = method.parameterTypes.toList() + method.returnType
                types.filterNot { it.isPrimitive }.forEach { type ->
                    assertThat(type.packageName)
                        .describedAs("%s.%s uses %s", port.simpleName, method.name, type.name)
                        .isEqualTo("club.podlodka.snowball.domain")
                }
            }
        }
    }
}
