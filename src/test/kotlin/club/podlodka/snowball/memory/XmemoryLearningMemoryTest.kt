package club.podlodka.snowball.memory

import club.podlodka.snowball.adapter.memory.XmemoryError
import club.podlodka.snowball.adapter.memory.XmemoryHttp
import club.podlodka.snowball.adapter.memory.XmemoryLearningMemory
import club.podlodka.snowball.config.XmemoryConfig
import club.podlodka.snowball.domain.CommittedDocs
import club.podlodka.snowball.domain.ContractJson
import club.podlodka.snowball.domain.DayType
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.Lesson
import club.podlodka.snowball.domain.LessonKey
import club.podlodka.snowball.domain.LessonScope
import club.podlodka.snowball.domain.PromotionCase
import club.podlodka.snowball.domain.PromotionDecisionEvent
import club.podlodka.snowball.domain.SimulatorVersion
import club.podlodka.snowball.domain.StockLevel
import club.podlodka.snowball.domain.Weather
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
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
 *
 * Every stubbed reply below is copied from a recorded answer of the real service. An earlier
 * version of this file invented the response shape instead, and every test passed while the
 * adapter could not read a single stored field back.
 */
class XmemoryLearningMemoryTest {
    private lateinit var server: HttpServer
    private lateinit var memory: XmemoryLearningMemory
    private lateinit var http: XmemoryHttp
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
        http =
            XmemoryHttp(
                XmemoryConfig(
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    instanceId = "test-instance",
                    apiKey = "test-key",
                ),
            )
        memory = XmemoryLearningMemory(http)
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

        val write = requests.first { it.first == "write" }.second
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
        // `xresponse` renders the query text rather than the scope - the same scoped read answered
        // with one field for a vague query and with all of them for a precise one. `raw-tables`
        // returns the stored columns and rows and does not depend on the wording.
        assertThat(read.path("mode").asText()).isEqualTo("raw-tables")
    }

    @Test
    fun `prose instead of rows is not read as a lesson`() {
        // The service can answer in prose - that is what its natural-language mode does, and one
        // wrong `mode` value is enough to get it. Prose carries no recommendation, so the only
        // honest answer is "no lesson", never a default one.
        reply = { """{"ids":[],"items":[{"reader_result":{"answer":"The lesson recommends 30%."}}],"errors":[]}""" }

        assertThat(memory.lesson(key)).isNull()
    }

    @Test
    fun `a row of some other object type is not read as a lesson`() {
        reply = {
            """{"ids":[],"items":[{"reader_result":{"columns":[
            {"name":"sku_id","type":"text"},{"name":"sku_name","type":"text"}],
            "rows":[["ICE500","Ice Cream 500ml"]]}}],"errors":[]}"""
        }

        assertThat(memory.lesson(key)).isNull()
    }

    @Test
    fun `a lesson row missing its recommendation is refused, not defaulted to zero`() {
        // The dangerous case. Reading an absent `recommended_discount` as 0 does not produce a
        // missing lesson - it produces a confident instruction to give no discount, carrying the
        // evidence count and confidence of a real one. The agent would follow it.
        reply = {
            """{"ids":[],"items":[{"reader_result":{"columns":[
            {"name":"lesson_key","type":"text"},{"name":"evidence_count","type":"integer"},
            {"name":"confidence","type":"float"},{"name":"rationale","type":"text"}],
            "rows":[["${key.wire}",9,0.95,"trust me"]]}}],"errors":[]}"""
        }

        assertThat(memory.lesson(key)).isNull()
    }

    @Test
    fun `an evidence row with an unreadable number is dropped, not read as zero profit`() {
        // A zero profit is a claim about the market: it would drag the aggregate down and could
        // flip which action a lesson recommends.
        reply = {
            """{"ids":[],"items":[{"reader_result":{"columns":[
            {"name":"case_id","type":"text"},{"name":"profit_0","type":"float"},
            {"name":"profit_10","type":"float"},{"name":"profit_20","type":"float"},
            {"name":"profit_30","type":"float"},{"name":"best_discount","type":"integer"}],
            "rows":[["CASE-good",1.0,2.0,3.0,4.0,30],["CASE-bad",1.0,null,3.0,4.0,10]]}}],"errors":[]}"""
        }

        val evidence = memory.casesFor(key)

        assertThat(evidence.map { it.caseId }).containsExactly("CASE-good")
    }

    @Test
    fun `an evidence row whose best discount is not an allowed action is dropped`() {
        reply = {
            """{"ids":[],"items":[{"reader_result":{"columns":[
            {"name":"case_id","type":"text"},{"name":"profit_0","type":"float"},
            {"name":"profit_10","type":"float"},{"name":"profit_20","type":"float"},
            {"name":"profit_30","type":"float"},{"name":"best_discount","type":"integer"}],
            "rows":[["CASE-odd",1.0,2.0,3.0,4.0,17]]}}],"errors":[]}"""
        }

        assertThat(memory.casesFor(key)).isEmpty()
    }

    @Test
    fun `a duplicate reported as a 409 conflict is also an outcome, not a failure`() {
        // The service reports the same condition three ways. Knowing only the 400 wording was
        // enough to pass every test and still abort a bulk write partway through.
        var writes = 0
        status = 409
        reply = { path ->
            if (path == "write" && writes++ == 0) {
                """{"ids":[],"items":[],"errors":[{"code":"CONFLICT",
                "message":"Cannot save this write: an existing Lesson record already has the same primary key."}]}"""
            } else {
                status = 200
                """{"ids":[],"items":[{}],"errors":[]}"""
            }
        }

        memory.saveLesson(Lesson(key, Discount.TWENTY, 3, BigDecimal("1.00"), BigDecimal("0.50"), "why"))

        val mutations = objectMutations()
        assertThat(mutations).hasSize(2)
        assertThat(mutations[1].path("object_mutation").has("update")).isTrue()
    }

    @Test
    fun `a record that exists but does not resolve yet is retried, not abandoned`() {
        // Measured on the real service under a bulk write: `create` answers "already exists" while
        // `update` for the same key answers "no such record", because the uniqueness check and the
        // key lookup do not become consistent at the same moment.
        var writes = 0
        reply = { path ->
            if (path != "write") {
                """{"ids":[],"items":[{}],"errors":[]}"""
            } else {
                when (writes++) {
                    0 -> {
                        status = 409
                        """{"ids":[],"items":[],"errors":[{"code":"CONFLICT",
                        "message":"an existing Lesson record already has the same primary key."}]}"""
                    }

                    1 -> {
                        status = 400
                        """{"ids":[],"items":[],"errors":[{"code":"VALIDATION_ERROR",
                        "message":"No 'Lesson' matches the given key."}]}"""
                    }

                    else -> {
                        status = 200
                        """{"ids":[],"items":[{}],"errors":[]}"""
                    }
                }
            }
        }

        memory.saveLesson(Lesson(key, Discount.TWENTY, 3, BigDecimal("1.00"), BigDecimal("0.50"), "why"))

        val mutations = objectMutations()
        assertThat(mutations).hasSize(3)
        assertThat(mutations.drop(1)).allSatisfy { assertThat(it.path("object_mutation").has("update")).isTrue() }
    }

    @Test
    fun `a missing instance is refused before the run starts`() {
        // A mistyped instance id answers 404 on every call, and the agent's own resilience then
        // hides it: it logs "memory unavailable" per scenario and decides without lessons, so a
        // benchmark arm reports fifty ordinary-looking results for a memory that was never there.
        status = 404
        reply = { """{"ids":[],"items":[],"errors":[{"code":"NOT_FOUND","message":"Resource not found"}]}""" }

        assertThatExceptionOfType(XmemoryError::class.java)
            .isThrownBy { http.requireInstance() }
            .withMessageContaining("not usable")
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
                """{"ids":[],"items":[{"trace_id":"918160_ett","reader_result":{"columns":[
                {"name":"lesson_key","type":"text"},{"name":"recommended_discount","type":"integer"},
                {"name":"rationale","type":"text"},{"name":"evidence_count","type":"integer"},
                {"name":"confidence","type":"float"},{"name":"avg_profit_advantage_pct","type":"float"}],
                "rows":[["${key.wire}",20,"because numbers",4,0.8,12.5]]},"sql":null}],"errors":[]}"""
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
    fun `evidence is read by traversing the relation, and that read is not scoped`() {
        reply = { path ->
            if (path == "read") {
                """{"ids":[],"items":[{"trace_id":"513948_kyg","reader_result":{"columns":[
                {"name":"case_id","type":"text"},{"name":"profit_0","type":"float"},
                {"name":"profit_10","type":"float"},{"name":"profit_20","type":"float"},
                {"name":"profit_30","type":"float"},{"name":"best_discount","type":"integer"}],
                "rows":[["CASE-v1-SCN-1",10.0,20.0,15.0,5.0,10]]},"sql":null}],"errors":[]}"""
            } else {
                """{"ids":[],"items":[{}],"errors":[]}"""
            }
        }

        val evidence = memory.casesFor(key)

        assertThat(evidence).hasSize(1)
        assertThat(evidence.single().bestDiscount).isEqualTo(Discount.TEN)
        assertThat(evidence.single().profitByDiscount.getValue(Discount.TEN)).isEqualByComparingTo("20.0")

        // A scope restricts a read to the objects it names: `all_relations` exposes the relations
        // among those, it does not reach their neighbours. Scoping this read to the lesson would
        // hide the very cases it asks for, which is how it returned nothing for a full day.
        val read = requests.last { it.first == "read" }.second
        assertThat(read.has("scope")).isFalse()
        assertThat(read.path("query").asText()).contains("lesson_evidence").contains(key.wire)
    }

    @Test
    fun `a relation names participant roles, not object types`() {
        memory.linkCaseToLesson("CASE-v1-SCN-1", key)

        val endpoints =
            requests
                .last { it.first == "write" }
                .second
                .path("structured_mutations")
                .first()
                .path("relation_mutation")
                .path("create")
                .path("endpoints")
        // Naming the types - `Lesson`, `PromotionCase` - parses and is then rejected for missing
        // both participant roles, which reads like a key problem and is not one.
        assertThat(endpoints.map { it.path("object_name").asText() }).containsExactlyInAnyOrder("lesson", "case")
        assertThat(
            endpoints
                .first { it.path("object_name").asText() == "case" }
                .path("key")
                .path("case_id")
                .asText(),
        ).isEqualTo("CASE-v1-SCN-1")
    }

    @Test
    fun `a read of an absent key is an empty answer, not a failure`() {
        // The service reports "no such record" as HTTP 400. Treating that as an outage cost the
        // agent its whole memory: it logged "memory unavailable" and decided as if untrained.
        status = 400
        reply = {
            """{"ids":[],"items":[],"errors":[{"code":"VALIDATION_ERROR",
            "message":"No 'lesson' object matches the provided primary key."}]}"""
        }

        assertThat(memory.lesson(key)).isNull()
    }

    @Test
    fun `writing a record that already exists updates it instead of failing`() {
        // Re-running a training scenario must be allowed: a run is resumable, and a lesson is
        // rewritten as evidence accumulates.
        var writes = 0
        status = 400
        reply = { path ->
            if (path == "write" && writes++ == 0) {
                """{"ids":[],"items":[],"errors":[{"code":"VALIDATION_ERROR",
                "message":"A 'Lesson' with this primary key already exists."}]}"""
            } else {
                status = 200
                """{"ids":[],"items":[{}],"errors":[]}"""
            }
        }

        memory.saveLesson(Lesson(key, Discount.TWENTY, 3, BigDecimal("12.50"), BigDecimal("0.71"), "because"))

        val mutations = objectMutations()
        assertThat(mutations).hasSize(2)
        assertThat(mutations[0].path("object_mutation").has("create")).isTrue()
        assertThat(mutations[1].path("object_mutation").has("update")).isTrue()
    }

    /** The first mutation of every write that carries an object, in order; relations are left out. */
    private fun objectMutations(): List<JsonNode> =
        requests
            .filter { it.first == "write" }
            .map { it.second.path("structured_mutations").first() }
            .filter { it.has("object_mutation") }

    private fun relationMutations(): List<JsonNode> =
        requests
            .filter { it.first == "write" }
            .flatMap { it.second.path("structured_mutations") }
            .filter { it.has("relation_mutation") }
            .map { it.path("relation_mutation") }

    private fun case(): PromotionCase {
        val decision: PromotionDecisionEvent =
            ContractJson.mapper.readValue(
                CommittedDocs.read(CommittedDocs.DECISION_EXAMPLE),
            )
        return PromotionCase(
            caseId = "CASE-v1-SCN-1",
            scenarioId = "SCN-1",
            simulatorVersion = SimulatorVersion.V1,
            scenario = decision.scenario,
            chosenDiscount = Discount.TEN,
            chosenUnitsSold = 10,
            chosenGrossProfit = BigDecimal("10.00"),
            profitByDiscount = Discount.entries.associateWith { BigDecimal(it.percent) },
            bestDiscount = Discount.THIRTY,
        )
    }

    @Test
    fun `a case is written after its product, and travels with the relation between them`() {
        memory.saveCase(case())

        // The product first, on its own: it is the record every case of that SKU shares, and a
        // batch carrying its create would be refused as a whole once it exists.
        val sku = objectMutations().first().path("object_mutation")
        assertThat(sku.path("object_type").asText()).isEqualTo("SKU")
        val values = sku.path("create").path("values")
        assertThat(
            sku
                .path("create")
                .path("key")
                .path("sku_id")
                .asText(),
        ).isEqualTo("ICE500")
        assertThat(values.path("name").asText()).isEqualTo("Ice Cream 500ml")
        assertThat(values.path("category").asText()).isEqualTo("ice_cream")
        assertThat(values.path("base_price").decimalValue()).isEqualByComparingTo("5.0")
        assertThat(values.path("cost").decimalValue()).isEqualByComparingTo("3.0")
        // Only the fields the schema declares; an unknown one rejects the whole write.
        assertThat(
            values.fieldNames().asSequence().toList(),
        ).containsExactlyInAnyOrder("name", "category", "base_price", "cost")

        // Then the case and its `case_sku` in one batch, so the relation never waits for the case
        // to become resolvable.
        val batch =
            requests
                .filter { it.first == "write" }
                .last()
                .second
                .path("structured_mutations")
        assertThat(batch).hasSize(2)
        assertThat(batch[0].path("object_mutation").path("object_type").asText()).isEqualTo("PromotionCase")
        val relation = batch[1].path("relation_mutation")
        assertThat(relation.path("relation_type").asText()).isEqualTo("case_sku")
        val endpoints =
            relation.path("create").path("endpoints").associate {
                it.path("object_name").asText() to
                    it.path("key")
            }
        assertThat(endpoints.keys).containsExactlyInAnyOrder("case", "sku")
        assertThat(endpoints.getValue("case").path("case_id").asText()).isEqualTo("CASE-v1-SCN-1")
        assertThat(endpoints.getValue("sku").path("sku_id").asText()).isEqualTo("ICE500")
    }

    @Test
    fun `a SKU lesson is linked to its one product by role`() {
        memory.saveLesson(Lesson(key, Discount.TWENTY, 3, BigDecimal("12.50"), BigDecimal("0.71"), "because numbers"))

        val scope = relationMutations().single { it.path("relation_type").asText() == "lesson_sku_scope" }
        val endpoints =
            scope.path("create").path("endpoints").associate {
                it.path("object_name").asText() to
                    it.path("key")
            }
        assertThat(endpoints.keys).containsExactlyInAnyOrder("lesson", "sku")
        assertThat(endpoints.getValue("lesson").path("lesson_key").asText()).isEqualTo(key.wire)
        assertThat(endpoints.getValue("sku").path("sku_id").asText()).isEqualTo("ICE500")
        // The lesson exists before anything points at it.
        assertThat(
            objectMutations()
                .single()
                .path("object_mutation")
                .path("object_type")
                .asText(),
        ).isEqualTo("Lesson")
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
