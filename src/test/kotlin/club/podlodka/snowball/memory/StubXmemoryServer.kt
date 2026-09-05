package club.podlodka.snowball.memory

import club.podlodka.snowball.domain.ContractJson
import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * A durable stand-in for xmemory: keyed records and relations that outlive any one client.
 *
 * The other tests in this package stub single responses, which is enough to check the shape of a
 * request. It is not enough to check that a record written by one process is readable by the next,
 * or that re-running a scenario does not count its evidence twice - those are claims about stored
 * state, so the stub has to hold state. Its rules are the ones the real service was measured to
 * enforce: `create` refuses an existing key, `update` refuses a missing one, a relation refuses a
 * duplicate, and reads answer with `raw-tables` columns and rows.
 */
class StubXmemoryServer {
    private val json = ContractJson.mapper
    private val objects = mutableMapOf<Pair<String, String>, MutableMap<String, JsonNode>>()
    private val links = mutableSetOf<Pair<String, String>>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    var writes = 0
        private set

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun start() {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path.substringAfterLast("/")
            val body = json.readTree(exchange.requestBody.readAllBytes())
            val (status, payload) =
                when (path) {
                    "write" -> write(body)
                    "read" -> read(body)
                    else -> 200 to """{"items":[{"instance_id":"stub"}],"errors":[]}"""
                }
            val bytes = payload.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    fun stop() = server.stop(0)

    /** What is actually stored, for assertions that should not go through the client under test. */
    fun objectsOf(type: String): List<Map<String, JsonNode>> =
        objects.filterKeys { it.first == type }.values.map { it.toMap() }

    fun linkCount(): Int = links.size

    private fun write(body: JsonNode): Pair<Int, String> {
        writes += 1
        body.path("structured_mutations").forEach { mutation ->
            mutation.path("object_mutation").takeIf { !it.isMissingNode }?.let { m ->
                val type = m.path("object_type").asText()
                val create = m.has("create")
                val op = m.path(if (create) "create" else "update")
                val keyField = op.path("key").fieldNames().next()
                val id = op.path("key").path(keyField).asText()
                val existing = objects[type to id]
                if (create && existing != null) {
                    return 400 to error("A '$type' with this primary key already exists.")
                }
                if (!create && existing == null) {
                    return 400 to error("No '$type' object matches the provided primary key.")
                }
                val record = existing ?: mutableMapOf(keyField to op.path("key").path(keyField))
                op.path("values").fields().forEach { (name, value) -> record[name] = value }
                objects[type to id] = record
            }
            mutation.path("relation_mutation").takeIf { !it.isMissingNode }?.let { m ->
                val endpoints = m.path("create").path("endpoints").associate { it.path("object_name").asText() to it }
                val lesson =
                    endpoints
                        .getValue("lesson")
                        .path("key")
                        .path("lesson_key")
                        .asText()
                val case =
                    endpoints
                        .getValue("case")
                        .path("key")
                        .path("case_id")
                        .asText()
                if (!links.add(lesson to case)) {
                    return 400 to
                        error(
                            "A '${m.path("relation_type").asText()}' relation matching its unique key " +
                                "(case, lesson) already exists.",
                        )
                }
            }
        }
        return 200 to """{"ids":[],"items":[{}],"errors":[]}"""
    }

    private fun read(body: JsonNode): Pair<Int, String> {
        val scope = body.path("scope").path("objects").firstOrNull()
        if (scope == null) {
            // The only unscoped read the client makes is the evidence traversal.
            val rows =
                links.mapNotNull { (lesson, case) ->
                    objects["PromotionCase" to case]?.let { record ->
                        listOf(json.getNodeFactory().textNode(lesson)) + EVIDENCE.map { record[it] ?: NULL }
                    }
                }
            return 200 to table(listOf("lesson_key") + EVIDENCE, rows)
        }
        val type = scope.path("type").asText()
        val keyNode = scope.path("key").path("key")
        val id = keyNode.path(keyNode.fieldNames().next()).asText()
        val record = objects[type to id] ?: return 400 to error("No '$type' object matches the provided primary key.")
        return 200 to table(record.keys.toList(), listOf(record.values.toList()))
    }

    private fun table(
        columns: List<String>,
        rows: List<List<JsonNode>>,
    ): String {
        val cols = columns.joinToString(",") { """{"name":"$it","type":"text"}""" }
        val body = rows.joinToString(",") { row -> row.joinToString(",", "[", "]") { it.toString() } }
        return """{"ids":[],"items":[{"reader_result":{"columns":[$cols],"rows":[$body]}}],"errors":[]}"""
    }

    private fun error(message: String): String =
        """{"ids":[],"items":[],"errors":[{"code":"VALIDATION_ERROR","message":"$message"}]}"""

    private companion object {
        private val NULL = ContractJson.mapper.nullNode()
        private val EVIDENCE =
            listOf("case_id", "profit_0", "profit_10", "profit_20", "profit_30", "best_discount")
    }
}
