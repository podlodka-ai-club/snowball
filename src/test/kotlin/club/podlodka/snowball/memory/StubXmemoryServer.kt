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
 * duplicate of its unique key and a participant that is not stored, a batch is all-or-nothing,
 * and reads answer with `raw-tables` columns and rows.
 */
class StubXmemoryServer {
    private val json = ContractJson.mapper
    private val objects = mutableMapOf<Pair<String, String>, MutableMap<String, JsonNode>>()
    private val links = mutableSetOf<Link>()
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    /** One stored relation: its type and, per participant role, the key value filling that role. */
    data class Link(
        val type: String,
        val endpoints: Map<String, String>,
    )

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

    /** Every stored relation of one type, each as its role-to-key map. */
    fun links(type: String): List<Map<String, String>> = links.filter { it.type == type }.map { it.endpoints }

    fun linkCount(type: String): Int = links(type).size

    private fun write(body: JsonNode): Pair<Int, String> {
        writes += 1
        // All-or-nothing, as measured: one rejected mutation leaves nothing of the batch behind.
        val objectsBefore = objects.mapValuesTo(mutableMapOf()) { it.value.toMutableMap() }
        val linksBefore = links.toSet()
        val rejection = body.path("structured_mutations").firstNotNullOfOrNull(::apply)
        if (rejection != null) {
            objects.clear()
            objects.putAll(objectsBefore)
            links.clear()
            links.addAll(linksBefore)
            return 400 to error(rejection)
        }
        return 200 to """{"ids":[],"items":[{}],"errors":[]}"""
    }

    /** Applies one mutation, answering with the service's rejection message if it refuses it. */
    private fun apply(mutation: JsonNode): String? {
        mutation.path("object_mutation").takeIf { !it.isMissingNode }?.let { m ->
            val type = m.path("object_type").asText()
            val create = m.has("create")
            val op = m.path(if (create) "create" else "update")
            val keyField = op.path("key").fieldNames().next()
            val id = op.path("key").path(keyField).asText()
            val existing = objects[type to id]
            if (create && existing != null) {
                return "A '$type' with this primary key already exists."
            }
            if (!create && existing == null) {
                return "No '$type' object matches the provided primary key."
            }
            val record = existing ?: mutableMapOf(keyField to op.path("key").path(keyField))
            op.path("values").fields().forEach { (name, value) -> record[name] = value }
            objects[type to id] = record
        }
        mutation.path("relation_mutation").takeIf { !it.isMissingNode }?.let { m ->
            val type = m.path("relation_type").asText()
            val relation = RELATIONS[type] ?: return "Unknown relation type '$type'."
            val endpoints =
                m.path("create").path("endpoints").associate { endpoint ->
                    val key = endpoint.path("key")
                    endpoint.path("object_name").asText() to key.path(key.fieldNames().next()).asText()
                }
            relation.roles.forEach { (role, objectType) ->
                val id = endpoints[role] ?: return "Relation '$type' is missing participant role(s): $role."
                // The real service resolves a participant by primary key, and refuses a relation
                // to a record it cannot resolve - which is what the ordering of writes is for.
                if (objects[objectType to id] == null) return "Participant '$role' ($objectType) was not found."
            }
            val unique = relation.unique.associateWith(endpoints::get)
            if (links.any { it.type == type && relation.unique.associateWith(it.endpoints::get) == unique }) {
                return "A '$type' relation matching its unique key (${relation.unique.joinToString(
                    ", ",
                )}) already exists."
            }
            links += Link(type, endpoints)
        }
        return null
    }

    private fun read(body: JsonNode): Pair<Int, String> {
        val scope = body.path("scope").path("objects").firstOrNull()
        if (scope == null) {
            // The client makes two unscoped reads, both traversals: the evidence behind lessons,
            // and the product list. Told apart by what the query names, as the real reader would.
            return if (body.path("query").asText().contains("lesson_evidence")) evidence() else skus()
        }
        val type = scope.path("type").asText()
        val keyNode = scope.path("key").path("key")
        val id = keyNode.path(keyNode.fieldNames().next()).asText()
        val record = objects[type to id] ?: return 400 to error("No '$type' object matches the provided primary key.")
        return 200 to table(record.keys.toList(), listOf(record.values.toList()))
    }

    private fun evidence(): Pair<Int, String> {
        val rows =
            links("lesson_evidence").mapNotNull { link ->
                objects["PromotionCase" to link.getValue("case")]?.let { record ->
                    listOf(json.getNodeFactory().textNode(link.getValue("lesson"))) +
                        EVIDENCE.map { record[it] ?: NULL }
                }
            }
        return 200 to table(listOf("lesson_key") + EVIDENCE, rows)
    }

    private fun skus(): Pair<Int, String> {
        val rows = objectsOf("SKU").map { record -> SKU.map { record[it] ?: NULL } }
        return 200 to table(SKU, rows)
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

    /** A relation as `docs/xmemory/schema.xmd.yaml` declares it: role to object type, and the unique key. */
    private data class Relation(
        val roles: Map<String, String>,
        val unique: List<String>,
    )

    private companion object {
        private val NULL = ContractJson.mapper.nullNode()
        private val EVIDENCE =
            listOf("case_id", "profit_0", "profit_10", "profit_20", "profit_30", "best_discount")
        private val SKU = listOf("sku_id", "category")
        private val RELATIONS =
            mapOf(
                "case_sku" to Relation(mapOf("case" to "PromotionCase", "sku" to "SKU"), listOf("case")),
                "lesson_evidence" to
                    Relation(mapOf("lesson" to "Lesson", "case" to "PromotionCase"), listOf("lesson", "case")),
                "lesson_sku_scope" to Relation(mapOf("lesson" to "Lesson", "sku" to "SKU"), listOf("lesson", "sku")),
            )
    }
}
