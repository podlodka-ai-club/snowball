package club.podlodka.snowball.application

import club.podlodka.snowball.adapter.context.DeterministicContextEnricher
import club.podlodka.snowball.adapter.inprocess.RecordingScenarioPublisher
import club.podlodka.snowball.adapter.source.DatasetBaselineSource
import club.podlodka.snowball.domain.CommittedDocs
import club.podlodka.snowball.domain.ContractJson
import club.podlodka.snowball.domain.ContractViolation
import club.podlodka.snowball.domain.DatasetSplit
import club.podlodka.snowball.port.BaselineSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.StringReader
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ScenarioGenerationServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-18T06:00:00Z"), ZoneOffset.UTC)

    private fun committedFixture() =
        DatasetBaselineSource { javaClass.getResourceAsStream("/fixtures/baseline.csv")!!.reader() }

    private fun service(
        source: BaselineSource,
        publisher: RecordingScenarioPublisher,
    ) = ScenarioGenerationService(
        baselineSource = source,
        contextEnricher = DeterministicContextEnricher(),
        publisher = publisher,
        clock = clock,
    )

    @Test
    fun `every generated scenario satisfies the committed contract`() {
        val publisher = RecordingScenarioPublisher()

        val report = service(committedFixture(), publisher).generate()

        assertThat(report.published).isEqualTo(300)
        assertThat(report.rejected).isEmpty()
        publisher.published.forEach { event ->
            val document = ContractJson.mapper.readTree(ContractJson.mapper.writeValueAsString(event))
            assertThat(CommittedDocs.validate(CommittedDocs.SCENARIO_SCHEMA, document))
                .describedAs("scenario %s", event.scenarioId)
                .isEmpty()
        }
    }

    @Test
    fun `the fixed market is injected`() {
        val publisher = RecordingScenarioPublisher()

        service(committedFixture(), publisher).generate(DatasetSplit.BENCHMARK)

        assertThat(publisher.published).isNotEmpty()
        publisher.published.forEach {
            assertThat(it.scenario.storeId).isEqualTo("LONDON_CENTRAL")
            assertThat(it.scenario.storeName).isEqualTo("London Central")
        }
    }

    @Test
    fun `a rerun produces identical scenarios and identities`() {
        val first = RecordingScenarioPublisher()
        val second = RecordingScenarioPublisher()

        service(committedFixture(), first).generate()
        service(committedFixture(), second).generate()

        assertThat(second.published).isEqualTo(first.published)
    }

    @Test
    fun `scenario identity is built from the fixture date`() {
        val publisher = RecordingScenarioPublisher()

        service(committedFixture(), publisher).generate()

        publisher.published.forEach { event ->
            val expected = "SCN-${event.scenario.date.toString().replace(
                "-",
                "",
            )}-LONDON_CENTRAL-${event.scenario.skuId}"
            assertThat(event.scenarioId).isEqualTo(expected)
        }
        assertThat(publisher.published.map { it.scenarioId }.toSet()).hasSize(publisher.published.size)
    }

    @Test
    fun `generation can be limited to one split`() {
        val publisher = RecordingScenarioPublisher()

        val report = service(committedFixture(), publisher).generate(DatasetSplit.TRAINING)

        assertThat(report.published).isEqualTo(250)
    }

    @Test
    fun `an unreadable source is reported and publishes nothing`() {
        val publisher = RecordingScenarioPublisher()
        val broken = DatasetBaselineSource { StringReader("source_reference,sku_id\nref,ICE500") }

        val report = service(broken, publisher).generate()

        assertThat(report.published).isZero()
        assertThat(report.hasRejections).isTrue()
        assertThat(publisher.published).isEmpty()
    }

    @Test
    fun `an event failing its contract is not published and the reason is reported`() {
        val publisher = RecordingScenarioPublisher()
        val service =
            ScenarioGenerationService(
                baselineSource = committedFixture(),
                contextEnricher = DeterministicContextEnricher(),
                publisher = publisher,
                clock = clock,
                validate = { throw ContractViolation("contract check failed for ${'$'}{it.scenarioId}") },
            )

        val report = service.generate(DatasetSplit.BENCHMARK)

        assertThat(report.published).isZero()
        assertThat(publisher.published).isEmpty()
        assertThat(report.rejected).hasSize(50).allMatch { it.contains("contract check failed") }
    }

    @Test
    fun `scheduled and manual triggers share one workflow`() {
        val scheduled = RecordingScenarioPublisher()
        val manual = RecordingScenarioPublisher()
        val trigger = ScenarioGenerationTrigger(service(committedFixture(), scheduled))
        val manualTrigger = ScenarioGenerationTrigger(service(committedFixture(), manual))

        trigger.onSchedule()
        manualTrigger.runNow()

        assertThat(manual.published).isEqualTo(scheduled.published)
    }
}
