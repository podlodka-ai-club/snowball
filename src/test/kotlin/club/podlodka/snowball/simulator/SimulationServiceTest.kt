package club.podlodka.snowball.simulator

import club.podlodka.snowball.adapter.inprocess.RecordingOutcomeSink
import club.podlodka.snowball.adapter.simulator.SimulationEngine
import club.podlodka.snowball.application.SimulationService
import club.podlodka.snowball.domain.CommittedDocs
import club.podlodka.snowball.domain.ContractJson
import club.podlodka.snowball.domain.PromotionDecisionEvent
import club.podlodka.snowball.domain.PromotionOutcomeEvent
import club.podlodka.snowball.domain.SimulatorVersion
import club.podlodka.snowball.port.OutcomeSink
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SimulationServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-18T06:00:02Z"), ZoneOffset.UTC)

    private fun decision(): PromotionDecisionEvent =
        ContractJson.mapper.readValue(CommittedDocs.read(CommittedDocs.DECISION_EXAMPLE))

    private fun service(sink: OutcomeSink) = SimulationService(SimulationEngine(), sink, clock)

    @Test
    fun `the committed decision example produces the committed outcome example`() {
        // The strongest check available on the formula: both documents were committed before any
        // code existed, so agreeing with them is agreeing with the specification rather than with
        // my own arithmetic.
        val sink = RecordingOutcomeSink()

        val produced = service(sink).simulate(decision())

        val expected =
            ContractJson.mapper.readValue<PromotionOutcomeEvent>(CommittedDocs.read(CommittedDocs.OUTCOME_EXAMPLE))
        assertThat(produced.outcome.unitsSold).isEqualTo(expected.outcome.unitsSold)
        assertThat(produced.outcome.grossProfit).isEqualByComparingTo(expected.outcome.grossProfit)
        assertThat(produced.outcomeId).isEqualTo(expected.outcomeId)
        assertThat(produced.simulatorVersion).isEqualTo(SimulatorVersion.V1)
    }

    @Test
    fun `the outcome satisfies its committed schema`() {
        val sink = RecordingOutcomeSink()

        val produced = service(sink).simulate(decision())

        val document = ContractJson.mapper.readTree(ContractJson.mapper.writeValueAsString(produced))
        assertThat(CommittedDocs.validate(CommittedDocs.OUTCOME_SCHEMA, document)).isEmpty()
    }

    @Test
    fun `the outcome carries no hidden ground truth`() {
        val sink = RecordingOutcomeSink()

        service(sink).simulate(decision())

        val printed = ContractJson.mapper.writeValueAsString(sink.received.single())
        assertThat(printed).doesNotContain("noise", "coefficient", "lift", "affinity", "oracle", "regret")
    }

    @Test
    fun `the scenario and decision cross unchanged`() {
        val sink = RecordingOutcomeSink()
        val input = decision()

        val produced = service(sink).simulate(input)

        assertThat(produced.scenario).isEqualTo(input.scenario)
        assertThat(produced.decision).isEqualTo(input.decision)
        assertThat(produced.scenarioId).isEqualTo(input.scenarioId)
        assertThat(produced.decisionId).isEqualTo(input.decisionId)
    }

    @Test
    fun `a failing sink fails the simulation rather than reporting success`() {
        val exploding = OutcomeSink { throw IllegalStateException("downstream unavailable") }

        assertThatExceptionOfType(IllegalStateException::class.java)
            .isThrownBy { service(exploding).simulate(decision()) }
            .withMessageContaining("downstream unavailable")
    }

    @Test
    fun `an unsupported category never reaches the sink`() {
        val sink = RecordingOutcomeSink()
        val unsupported = decision().let { it.copy(scenario = it.scenario.copy(category = "stationery")) }

        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { service(sink).simulate(unsupported) }
        assertThat(sink.received).isEmpty()
    }

    @Test
    fun `replaying the same decision reproduces the outcome exactly`() {
        val first = RecordingOutcomeSink()
        val second = RecordingOutcomeSink()

        service(first).simulate(decision())
        service(second).simulate(decision())

        assertThat(second.received.single()).isEqualTo(first.received.single())
    }
}
