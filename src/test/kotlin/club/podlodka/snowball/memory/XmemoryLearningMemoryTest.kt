package club.podlodka.snowball.memory

import club.podlodka.snowball.adapter.memory.XmemoryError
import club.podlodka.snowball.adapter.memory.XmemoryHttp
import club.podlodka.snowball.adapter.memory.XmemoryLearningMemory
import club.podlodka.snowball.config.XmemoryConfig
import club.podlodka.snowball.domain.ContractJson
import club.podlodka.snowball.domain.DayType
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.Lesson
import club.podlodka.snowball.domain.LessonKey
import club.podlodka.snowball.domain.LessonScope
import club.podlodka.snowball.domain.StockLevel
import club.podlodka.snowball.domain.Weather
import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress

/**
 * Tested against a local stub rather than the real service.
 *
 * The quota is shared and one overnight run has already been reported to eat a tenth of the
 * monthly allowance; spending it on assertions would be indefensible. What matters here is the
 * request this adapter builds and how it reacts to the answers - both checkable without a network.
 */
class XmemoryLearningMemoryTest {
    private lateinit var server: HttpServer
    private lateinit var memory: XmemoryLearningMemory
    private val requests = mutableListOf<Pair<String, JsonNode>>()
    private var reply: (String) -> String = { """{"ids":[],"items":[{}],"errors":[]}""" }
    private var status = 200

    private val key = LessonKey(LessonScope.SKU, "ICE500", DayType.WEEKEND, Weather.HOT, StockLevel.HIGH)

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path.substringAfterLast("/")
            val body = ContractJson.mapper.readTree(exchange.requestBody.readAllBytes())
            requests += path to body
            val payload = reply(path).toByteArray()
            exchange.sendResponseHeaders(status, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        memory =
            XmemoryLearningMemory(
                XmemoryHttp(
                    XmemoryConfig(
                        baseUrl = "http://127.0.0.1:${server.address.port}",
                        instanceId = "test-instance",
                        apiKey = "test-key",
                    ),
                ),
            )
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a lesson is written as a structured mutation, never as text`() {
        // Structured writes skip the model entirely; the text path would spend tokens on data we
        // already hold in typed form, which is the whole quota argument.
        memory.saveLesson(
            Lesson(key, Discount.TWENTY, 3, BigDecimal("12.50"), BigDecimal("0.71"), "because numbers"),
        )

        val write = requests.last { it.first == "write" }.second
        assertThat(write.has("text")).isFalse()
        assertThat(write.has("structured_mutations")).isTrue()
        val values = write.path("structured_mutations").first().path("object_mutation")
        assertThat(values.path("object_type").asText()).isEqualTo("Lesson")
        assertThat(
            values
                .path("create")
                .path("values")
                .path("recommended_discount")
                .asInt(),
        ).isEqualTo(20)
        assertThat(
            values
                .path("create")
                .path("values")
                .path("evidence_count")
                .asInt(),
        ).isEqualTo(3)
    }

    @Test
    fun `a read is scoped by key with the doubly nested shape the API requires`() {
        // Getting this wrong returns an empty result rather than an error, which is how the first
        // measurement quietly read nothing back.
        memory.lesson(key)

        val read = requests.last { it.first == "read" }.second
        val scoped = read.path("scope").path("objects").first()
        assertThat(scoped.path("type").asText()).isEqualTo("Lesson")
        assertThat(
            scoped
                .path("key")
                .path("key")
                .path("lesson_key")
                .asText(),
        ).isEqualTo(key.wire)
        assertThat(read.path("mode").asText()).isEqualTo("xresponse")
    }

    @Test
    fun `an exhausted quota is reported as such and not retried into the wall`() {
        status = 402
        reply = { """{"errors":[{"code":"QUOTA_EXCEEDED","message":"Daily token quota exceeded."}]}""" }

        assertThatExceptionOfType(XmemoryError::class.java)
            .isThrownBy { memory.lesson(key) }
            .withMessageContaining("quota")
    }

    @Test
    fun `an error inside a 200 envelope is still an error`() {
        // xmemory answers 200 with a populated errors array for validation failures; treating that
        // as success would silently drop writes, which is exactly what happened during the probe.
        reply = { """{"ids":[],"items":[],"errors":[{"code":"VALIDATION_ERROR","message":"Unknown field"}]}""" }

        assertThatExceptionOfType(XmemoryError::class.java)
            .isThrownBy { memory.saveLesson(Lesson(key, Discount.TEN, 1, BigDecimal.ZERO, BigDecimal.ZERO, "why")) }
            .withMessageContaining("VALIDATION_ERROR")
    }

    @Test
    fun `a stored lesson is read back`() {
        reply = { path ->
            if (path == "read") {
                """{"items":[{"reader_result":{"objects":[{"identifier":"lesson_key='${key.wire}'","fields":[
                {"name":"lesson_key","value":{"string_value":"${key.wire}"}},
                {"name":"recommended_discount","value":{"integer_value":20}},
                {"name":"evidence_count","value":{"integer_value":4}},
                {"name":"avg_profit_advantage_pct","value":{"float_value":12.5}},
                {"name":"confidence","value":{"float_value":0.8}},
                {"name":"rationale","value":{"string_value":"because numbers"}}]}]}}],"errors":[]}"""
            } else {
                """{"ids":[],"items":[{}],"errors":[]}"""
            }
        }

        val lesson = memory.lesson(key)

        assertThat(lesson).isNotNull
        assertThat(lesson!!.recommendedDiscount).isEqualTo(Discount.TWENTY)
        assertThat(lesson.evidenceCount).isEqualTo(4)
        assertThat(lesson.rationale).isEqualTo("because numbers")
    }

    @Test
    fun `evidence is read through the lesson's relations`() {
        reply = { path ->
            if (path == "read") {
                """{"items":[{"reader_result":{"objects":[{"name":"PromotionCase","fields":[
                {"name":"case_id","value":{"string_value":"CASE-v1-SCN-1"}},
                {"name":"profit_0","value":{"float_value":10.0}},
                {"name":"profit_10","value":{"float_value":20.0}},
                {"name":"profit_20","value":{"float_value":15.0}},
                {"name":"profit_30","value":{"float_value":5.0}},
                {"name":"best_discount","value":{"integer_value":10}}]}]}}],"errors":[]}"""
            } else {
                """{"ids":[],"items":[{}],"errors":[]}"""
            }
        }

        val evidence = memory.casesFor(key)

        assertThat(evidence).hasSize(1)
        assertThat(evidence.single().bestDiscount).isEqualTo(Discount.TEN)
        assertThat(evidence.single().profitByDiscount.getValue(Discount.TEN)).isEqualByComparingTo("20.0")
        val read = requests.last { it.first == "read" }.second
        assertThat(read.path("scope").path("relations_scope").asText()).isEqualTo("all_relations")
    }

    @Test
    fun `linking a case to a lesson writes a relation, not another object`() {
        memory.linkCaseToLesson("CASE-v1-SCN-1", key)

        val mutation =
            requests
                .last { it.first == "write" }
                .second
                .path("structured_mutations")
                .first()
        assertThat(mutation.has("relation_mutation")).isTrue()
        assertThat(mutation.path("relation_mutation").path("relation_type").asText()).isEqualTo("lesson_evidence")
    }
}
