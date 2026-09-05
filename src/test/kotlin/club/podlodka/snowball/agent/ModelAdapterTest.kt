package club.podlodka.snowball.agent

import club.podlodka.snowball.adapter.model.OpenAiCompatibleDecisionModel
import club.podlodka.snowball.domain.CommittedDocs
import club.podlodka.snowball.domain.ContractJson
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.PromotionScenario
import club.podlodka.snowball.domain.PromotionScenarioEvent
import com.fasterxml.jackson.module.kotlin.readValue
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

/** The model adapter against a stub, so every failure path is reachable without a server. */
class ModelAdapterTest {
    private lateinit var server: HttpServer
    private var body: String = ""
    private var status = 200

    private fun scenario(): PromotionScenario =
        ContractJson.mapper
            .readValue<PromotionScenarioEvent>(CommittedDocs.read(CommittedDocs.SCENARIO_EXAMPLE))
            .scenario

    private fun model() =
        OpenAiCompatibleDecisionModel(
            baseUrl = "http://127.0.0.1:${server.address.port}/v1",
            modelId = "stub-model",
        )

    private fun completion(
        content: String,
        finish: String = "stop",
    ) = """{"choices":[{"finish_reason":"$finish","message":{"content":${ContractJson.mapper.writeValueAsString(
        content,
    )}}}]}"""

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.readAllBytes()
            val payload = body.toByteArray()
            exchange.sendResponseHeaders(status, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `the documented JSON answer is accepted`() {
        body = completion("""{"discount": 20}""")

        assertThat(model().choose(scenario(), emptyList())?.discount).isEqualTo(Discount.TWENTY)
    }

    @Test
    fun `a bare allowed number is accepted`() {
        body = completion("30")

        assertThat(model().choose(scenario(), emptyList())?.discount).isEqualTo(Discount.THIRTY)
    }

    @Test
    fun `a discount outside the allowed four is refused, not rounded`() {
        // Guessing what "25" meant would put an action into the record that the contract forbids.
        body = completion("""{"discount": 25}""")

        assertThat(model().choose(scenario(), emptyList())).isNull()
    }

    @Test
    fun `prose without a decision is refused`() {
        body = completion("I would suggest a moderate discount.")

        assertThat(model().choose(scenario(), emptyList())).isNull()
    }

    @Test
    fun `an answer truncated before content is refused rather than misread`() {
        // Measured against the real server: when the token budget runs out inside the reasoning
        // block, content comes back empty with finish_reason=length. Treating that as a decision
        // would silently turn a truncation into a policy.
        body = completion("", finish = "length")

        assertThat(model().choose(scenario(), emptyList())).isNull()
    }

    @Test
    fun `a server error is refused, not retried into a wrong answer`() {
        status = 500
        body = "upstream on fire"

        assertThat(model().choose(scenario(), emptyList())).isNull()
    }
}
