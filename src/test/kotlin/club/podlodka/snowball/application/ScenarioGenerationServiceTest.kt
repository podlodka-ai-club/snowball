package club.podlodka.snowball.application

import club.podlodka.snowball.adapter.context.DeterministicContextEnricher
import club.podlodka.snowball.adapter.inprocess.RecordingScenarioPublisher
import club.podlodka.snowball.adapter.source.DatasetBaselineSource
import club.podlodka.snowball.domain.CommittedDocs
import club.podlodka.snowball.domain.ContractJson
import club.podlodka.snowball.domain.ContractViolation
import club.podlodka.snowball.domain.DatasetSplit
import club.podlodka.snowball.port.BaselineSource
import club.podlodka.snowball.port.ScenarioPublisher
import club.podlodka.snowball.port.SimulationPort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
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
        validate: ((club.podlodka.snowball.domain.PromotionScenarioEvent) -> Unit)? = null,
    ) = if (validate == null) {
        ScenarioGenerationService(source, DeterministicContextEnricher(), publisher, clock = clock)
    } else {
        ScenarioGenerationService(source, DeterministicContextEnricher(), publisher, clock = clock, validate = validate)
    }

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
    fun `the same service run twice hands off again rather than deduplicating`() {
        // At-least-once is the downstream's problem, handled there by scenario_id. A generator
        // that silently swallowed the second handoff would look correct in a rerun test that
        // builds a fresh service each time, and would quietly halve a repeated training run.
        val publisher = RecordingScenarioPublisher()
        val service = service(committedFixture(), publisher)

        service.generate(DatasetSplit.BENCHMARK)
        service.generate(DatasetSplit.BENCHMARK)

        assertThat(publisher.published).hasSize(100)
    }

    @Test
    fun `scenario identity is built from the fixture date and is unique`() {
        val publisher = RecordingScenarioPublisher()

        service(committedFixture(), publisher).generate()

        publisher.published.forEach { event ->
            val expected =
                "SCN-${event.scenario.date.toString().replace("-", "")}-LONDON_CENTRAL-${event.scenario.skuId}"
            assertThat(event.scenarioId).isEqualTo(expected)
        }
        assertThat(publisher.published.map { it.scenarioId }.toSet()).hasSize(publisher.published.size)
    }

    @Test
    fun `two rows that would share an identity are refused, not silently merged`() {
        val header = DatasetBaselineSource.REQUIRED_COLUMNS.joinToString(",")
        val a = "ref-A,2026-06-03,training,ICE500,Ice Cream 500ml,ice_cream,5.00,3.00,100,150"
        val b = "ref-B,2026-06-03,training,ICE500,Ice Cream 500ml,ice_cream,5.00,3.00,110,160"
        val publisher = RecordingScenarioPublisher()
        val source = DatasetBaselineSource { StringReader("$header\n$a\n$b") }

        val report = service(source, publisher).generate()

        assertThat(report.published).isEqualTo(1)
        assertThat(report.rejected).hasSize(1)
        assertThat(report.rejected.single().reason).contains("collides")
        assertThat(report.rejected.single().scenarioId).isEqualTo("SCN-20260603-LONDON_CENTRAL-ICE500")
    }

    @Test
    fun `generation can be limited to one split`() {
        val publisher = RecordingScenarioPublisher()

        val report = service(committedFixture(), publisher).generate(DatasetSplit.TRAINING)

        assertThat(report.published).isEqualTo(250)
    }

    @Test
    fun `an unusable fixture is reported and publishes nothing`() {
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
            service(
                committedFixture(),
                publisher,
            ) { throw ContractViolation("contract check failed for ${it.scenarioId}") }

        val report = service.generate(DatasetSplit.BENCHMARK)

        assertThat(report.published).isZero()
        assertThat(publisher.published).isEmpty()
        assertThat(report.rejected).hasSize(50)
        assertThat(report.rejected).allMatch { it.reason.contains("contract check failed") }
        assertThat(report.rejected).allMatch { it.scenarioId != null && it.sourceReference != null }
    }

    @Test
    fun `the generator cannot reach simulation`() {
        // Checked on the constructor signature rather than on fields: Kotlin drops a private val
        // that the class body never reads, so a smuggled-in dependency can be invisible in
        // declaredFields while still being wired in by the caller.
        val injected =
            ScenarioGenerationService::class.java.declaredConstructors
                .flatMap { it.parameterTypes.asIterable() }
                .map { it.name }
                .toSet()

        assertThat(injected).doesNotContain(SimulationPort::class.java.name)
        assertThat(injected.filter { it.startsWith("club.podlodka.snowball.port.") })
            .containsExactlyInAnyOrder(
                "club.podlodka.snowball.port.BaselineSource",
                "club.podlodka.snowball.port.ContextEnricher",
                "club.podlodka.snowball.port.ScenarioPublisher",
            )
    }

    @Test
    fun `scheduled and manual triggers share one workflow`() {
        // Comparing the two outputs is not enough on its own: if both triggers did nothing, the
        // comparison would still hold. Each must produce the full set as well as the same set.
        val scheduled = RecordingScenarioPublisher()
        val manual = RecordingScenarioPublisher()

        val fromSchedule = ScenarioGenerationTrigger(service(committedFixture(), scheduled)).onSchedule()
        val fromManual = ScenarioGenerationTrigger(service(committedFixture(), manual)).runNow()

        assertThat(fromSchedule.published).isEqualTo(300)
        assertThat(fromManual.published).isEqualTo(300)
        assertThat(manual.published).isEqualTo(scheduled.published)
    }

    @Test
    fun `rejections from the source reach the report`() {
        val header = DatasetBaselineSource.REQUIRED_COLUMNS.joinToString(",")
        val bad = "ref-BAD,2026-06-03,training,BAD1,Bad,ice_cream,5.00,3.00,0,150"
        val good = "ref-OK,2026-06-04,training,OK1,Ok,ice_cream,5.00,3.00,100,150"
        val publisher = RecordingScenarioPublisher()

        val report = service(DatasetBaselineSource { StringReader("$header\n$bad\n$good") }, publisher).generate()

        assertThat(report.published).isEqualTo(1)
        assertThat(report.rejected).hasSize(1)
        assertThat(report.rejected.single().sourceReference).isEqualTo("ref-BAD")
        assertThat(report.rejected.single().reason).contains("baseline_sales")
    }

    @Test
    fun `a duplicated row is published once and reported once`() {
        val header = DatasetBaselineSource.REQUIRED_COLUMNS.joinToString(",")
        val row = "ref-A,2026-06-03,training,ICE500,Ice Cream 500ml,ice_cream,5.00,3.00,100,150"
        val publisher = RecordingScenarioPublisher()

        val report = service(DatasetBaselineSource { StringReader("$header\n$row\n$row") }, publisher).generate()

        assertThat(report.published).isEqualTo(1)
        assertThat(publisher.published).hasSize(1)
        assertThat(report.rejected.single().reason).contains("duplicate row")
        assertThat(report.rejected.single().sourceReference).isEqualTo("ref-A")
        assertThat(report.rejected.single().scenarioId).isEqualTo("SCN-20260603-LONDON_CENTRAL-ICE500")
    }

    @Test
    fun `a collision names the reference it clashed with`() {
        val header = DatasetBaselineSource.REQUIRED_COLUMNS.joinToString(",")
        val a = "ref-A,2026-06-03,training,ICE500,Ice Cream 500ml,ice_cream,5.00,3.00,100,150"
        val b = "ref-B,2026-06-03,training,ICE500,Ice Cream 500ml,ice_cream,5.00,3.00,110,160"
        val publisher = RecordingScenarioPublisher()

        val report = service(DatasetBaselineSource { StringReader("$header\n$a\n$b") }, publisher).generate()

        val rejection = report.rejected.single()
        assertThat(rejection.reason).contains("ref-A")
        assertThat(rejection.sourceReference).isEqualTo("ref-B")
    }

    @Test
    fun `a failing publisher fails the cycle instead of being filed as a bad row`() {
        // The guide forbids reporting a cycle as successful when publication failed, so a
        // publisher error must not be quietly recorded as if the source data were at fault.
        val exploding =
            ScenarioPublisher { throw IllegalArgumentException("broker refused") }
        val service =
            ScenarioGenerationService(
                committedFixture(),
                DeterministicContextEnricher(),
                exploding,
                clock = clock,
            )

        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { service.generate(DatasetSplit.BENCHMARK) }
            .withMessageContaining("broker refused")
    }
}
