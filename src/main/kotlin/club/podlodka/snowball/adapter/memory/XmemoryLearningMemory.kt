package club.podlodka.snowball.adapter.memory

import club.podlodka.snowball.domain.CaseEvidence
import club.podlodka.snowball.domain.ContractJson
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.Lesson
import club.podlodka.snowball.domain.LessonKey
import club.podlodka.snowball.domain.LessonScope
import club.podlodka.snowball.domain.PromotionCase
import club.podlodka.snowball.port.LearningMemory
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.util.logging.Logger

/**
 * Durable learning memory backed by xmemory.
 *
 * Two rules shape this adapter, both from measurement rather than taste - see `GOTCHAS.md`.
 *
 * Writes use `structured_mutations`, which xmemory applies deterministically with no model
 * involved. That is what makes writing a few hundred training cases affordable at all; the text
 * path would spend model tokens on data we already have in typed form.
 *
 * Reads ask for `raw-tables`, which returns the stored columns and rows verbatim. The
 * alternative, `xresponse`, is a model's rendering of the query text rather than of the scope: the
 * same scoped read returned one field for a vaguely worded query and all twelve for a precise one,
 * so retrieval here is never conversational.
 */
class XmemoryLearningMemory(
    private val http: XmemoryHttp,
) : LearningMemory {
    private val json = ContractJson.mapper

    override fun saveCase(case: PromotionCase) {
        val values =
            json.createObjectNode().apply {
                put("store", case.scenario.storeId)
                put("price", case.scenario.price)
                put("baseline_sales", case.scenario.baselineSales)
                put("stock", case.scenario.stock)
                put("stock_level", case.scenario.stockLevel.wire)
                put("day_type", case.scenario.dayType.wire)
                put("weather", case.scenario.weather.wire)
                put("event_type", case.scenario.eventType.wire)
                put("chosen_discount", case.chosenDiscount.percent)
                put("units_sold", case.chosenUnitsSold)
                put("gross_profit", case.chosenGrossProfit)
                Discount.entries.forEach { put("profit_${it.percent}", case.profitByDiscount.getValue(it)) }
                put("best_discount", case.bestDiscount.percent)
                put("best_gross_profit", case.bestGrossProfit)
                put("regret", case.regret)
                put("regret_pct", case.regretPct)
                put("scenario_date", case.scenario.date.toString())
                case.scenario.temperatureC?.let { put("temperature_c", it) }
                case.scenario.eventNote?.let { put("event_note", it) }
            }
        upsert("PromotionCase", "case_id", case.caseId, values)
    }

    override fun findCase(caseId: String): PromotionCase? {
        // Presence is what the evaluator asks about - it uses this to avoid re-learning a case it
        // already recorded. A stored case cannot be rebuilt in full (its SKU and category live on
        // the related record), so answering with a half-invented one would be worse than saying
        // nothing; the evaluator re-derives the case from the outcome it already holds.
        return null
    }

    override fun linkCaseToLesson(
        caseId: String,
        key: LessonKey,
    ) {
        // `object_name` names the participant *role* from the schema relation - `lesson` and
        // `case` - not the object type. Naming the types instead is accepted as far as parsing and
        // then rejected for missing both roles, which reads like a key problem and is not one.
        val endpoints =
            json.createArrayNode().apply {
                add(endpoint("lesson", "lesson_key", key.wire))
                add(endpoint("case", "case_id", caseId))
            }
        val mutation =
            json.createObjectNode().apply {
                set<ObjectNode>(
                    "relation_mutation",
                    json.createObjectNode().apply {
                        put("relation_type", "lesson_evidence")
                        set<ObjectNode>("create", json.createObjectNode().set<ArrayNode>("endpoints", endpoints))
                    },
                )
            }
        // Re-linking a case already linked to this lesson is the resume path, not a failure.
        write(listOf(mutation), ALREADY_EXISTS)
    }

    /**
     * The evidence behind one lesson, read by walking the `lesson_evidence` relation.
     *
     * This read is deliberately unscoped. A `scope` restricts a read to the objects it lists and
     * nothing else - `all_relations` exposes the relations *among those* objects, it does not pull
     * in their neighbours - so scoping to the lesson makes its cases invisible by construction.
     * The traversal costs around 20 seconds on a key the service has not answered for before.
     */
    override fun casesFor(key: LessonKey): List<CaseEvidence> {
        val columns = Discount.entries.joinToString(", ") { "profit_${it.percent}" }
        return unscopedRead(
            "For the Lesson whose lesson_key is \"${key.wire}\", list every PromotionCase linked to it " +
                "through the lesson_evidence relation. Return case_id, $columns and best_discount.",
        ).mapNotNull(::toEvidence)
    }

    /**
     * Every recorded `lesson_evidence` link, keyed by the lesson it belongs to, in one call.
     *
     * A run that aggregates lessons needs all of this and nothing else, and asking for it per
     * lesson costs a model call each time. One traversal returns the whole join.
     */
    fun allEvidence(): Map<String, List<CaseEvidence>> {
        val columns = Discount.entries.joinToString(", ") { "profit_${it.percent}" }
        return unscopedRead(
            "List every lesson_evidence link in the instance. For each link return the Lesson " +
                "lesson_key and the linked PromotionCase case_id, $columns and best_discount.",
        ).filter { !it["lesson_key"]?.asText().isNullOrEmpty() }
            .mapNotNull { row -> toEvidence(row)?.let { row.getValue("lesson_key").asText() to it } }
            .groupBy({ it.first }, { it.second })
    }

    override fun saveLesson(lesson: Lesson) {
        val values =
            json.createObjectNode().apply {
                put("scope", "${lesson.key.scope.prefix}:${lesson.key.scopeValue}")
                put("store_scope", "any")
                put("day_type", lesson.key.dayType.wire)
                put("weather", lesson.key.weather.wire)
                put("event_type", "any")
                put("stock_level", lesson.key.stockLevel.wire)
                put("recommended_discount", lesson.recommendedDiscount.percent)
                put("rationale", lesson.rationale)
                put("evidence_count", lesson.evidenceCount)
                put("avg_profit_advantage_pct", lesson.avgProfitAdvantagePct)
                put("confidence", lesson.confidence)
            }
        // A lesson updates in place on its deterministic key, including when new evidence
        // overturns the recommendation - a contradiction is a changed lesson, not a second one.
        upsert("Lesson", "lesson_key", lesson.key.wire, values)
    }

    override fun lesson(key: LessonKey): Lesson? =
        scopedRead("Lesson", "lesson_key", key.wire)
            .firstOrNull { it["lesson_key"]?.asText() == key.wire }
            ?.let { toLesson(key, it) }

    private fun write(
        mutations: List<JsonNode>,
        tolerate: (String) -> Boolean = { false },
    ): JsonNode? {
        val body =
            json.createObjectNode().apply {
                set<ArrayNode>("structured_mutations", json.createArrayNode().addAll(mutations))
            }
        return http.post("/write", body, tolerate)
    }

    /**
     * Writes a record whether or not it is already there.
     *
     * A training run is resumable and a lesson is rewritten as evidence accumulates, so both
     * happen: `create` on a key that exists is refused, and `update` on one that does not. Rather
     * than reading first - a second round trip on every single write - this tries the create and
     * falls back, which costs the extra call only on the writes that need it.
     */
    private fun upsert(
        type: String,
        keyField: String,
        keyValue: String,
        values: ObjectNode,
    ) {
        val created = write(listOf(objectMutation(type, keyField, keyValue, values)), ALREADY_EXISTS)
        if (created == null) {
            write(listOf(objectMutation(type, keyField, keyValue, values, "update")))
        }
    }

    private fun objectMutation(
        type: String,
        keyField: String,
        keyValue: String,
        values: ObjectNode,
        operation: String = "create",
    ): JsonNode =
        json.createObjectNode().apply {
            set<ObjectNode>(
                "object_mutation",
                json.createObjectNode().apply {
                    put("object_type", type)
                    set<ObjectNode>(
                        operation,
                        json.createObjectNode().apply {
                            set<ObjectNode>("key", json.createObjectNode().put(keyField, keyValue))
                            set<ObjectNode>("values", values)
                        },
                    )
                },
            )
        }

    /**
     * A read anchored to one primary key. The key is nested twice - `key: { key: { field: value } }`
     * - which the API requires and which is easy to get wrong silently: the wrong shape returns an
     * empty result rather than an error.
     */
    private fun scopedRead(
        type: String,
        keyField: String,
        keyValue: String,
    ): List<Map<String, JsonNode>> {
        val body =
            json.createObjectNode().apply {
                put("query", "Return every stored field of the scoped $type records.")
                put("mode", "raw-tables")
                set<ObjectNode>(
                    "scope",
                    json.createObjectNode().apply {
                        set<ArrayNode>(
                            "objects",
                            json.createArrayNode().add(
                                json.createObjectNode().apply {
                                    put("type", type)
                                    set<ObjectNode>("key", keyNode(keyField, keyValue))
                                },
                            ),
                        )
                        put("relations_scope", "no_relations")
                    },
                )
            }
        return rows(http.post("/read", body, NO_SUCH_KEY))
    }

    /** A read over the whole instance, which is what a traversal across a relation requires. */
    private fun unscopedRead(query: String): List<Map<String, JsonNode>> {
        val body =
            json.createObjectNode().apply {
                put("query", query)
                put("mode", "raw-tables")
            }
        return rows(http.post("/read", body, NO_SUCH_KEY))
    }

    /** `raw-tables` answers with the column list and the rows under it; pair them up by position. */
    private fun rows(result: JsonNode?): List<Map<String, JsonNode>> {
        val reader = result?.path("reader_result") ?: return emptyList()
        val columns = reader.path("columns").map { it.path("name").asText() }
        return reader.path("rows").map { row -> columns.withIndex().associate { (i, name) -> name to row.path(i) } }
    }

    /** One participant of a relation: its role, and the primary key of the object filling it. */
    private fun endpoint(
        role: String,
        keyField: String,
        keyValue: String,
    ): ObjectNode =
        json
            .createObjectNode()
            .put("object_name", role)
            .set("key", json.createObjectNode().put(keyField, keyValue))

    private fun keyNode(
        field: String,
        value: String,
    ): ObjectNode = json.createObjectNode().set("key", json.createObjectNode().put(field, value))

    /**
     * A stored number, or null if the column is absent, empty or not a number.
     *
     * Never a default. An absent `recommended_discount` read as zero is not a missing value, it is
     * a lesson saying "give no discount" - carrying the confidence and the evidence count of a
     * real one. A memory that invents advice is worse than one that returns nothing.
     */
    private fun Map<String, JsonNode>.number(name: String): BigDecimal? =
        this[name]
            ?.takeIf { !it.isNull && !it.isMissingNode }
            ?.let { if (it.isNumber) it.decimalValue() else it.asText().trim().toBigDecimalOrNull() }

    /**
     * Only the columns a lesson aggregates. The stored case does not carry the SKU or category as
     * its own fields - they live on the related SKU record - so reconstructing a full case here
     * would mean inventing them. A row that cannot be read in full is dropped rather than
     * half-read into a case with defaults standing in for the missing numbers.
     */
    private fun toEvidence(row: Map<String, JsonNode>): CaseEvidence? {
        val caseId = row["case_id"]?.asText().orEmpty()
        val profits = Discount.entries.associateWith { row.number("profit_${it.percent}") }
        val best = row.number("best_discount")?.let(::discountOrNull)
        if (caseId.isEmpty() || profits.values.any { it == null } || best == null) {
            log.warning { "dropping an unreadable evidence row: ${row.keys}" }
            return null
        }
        return CaseEvidence(caseId, profits.mapValues { it.value!! }, best)
    }

    private fun discountOrNull(value: BigDecimal): Discount? =
        value.toIntOrNull()?.let { percent -> Discount.entries.firstOrNull { it.percent == percent } }

    private fun BigDecimal.toIntOrNull(): Int? = runCatching { intValueExact() }.getOrNull()

    /**
     * A stored row becomes a lesson only if it carries the two fields a lesson cannot be invented
     * without: which action it recommends, and how much evidence stands behind it. Anything else -
     * prose from a natural-language answer, a row of some other object type, a half-written record
     * - is refused here rather than handed to the agent as advice.
     */
    private fun toLesson(
        key: LessonKey,
        row: Map<String, JsonNode>,
    ): Lesson? {
        val recommended = row.number("recommended_discount")?.let(::discountOrNull)
        val evidence = row.number("evidence_count")?.toIntOrNull()
        if (recommended == null || evidence == null || evidence <= 0) {
            log.warning { "refusing an unreadable lesson row for ${key.wire}: ${row.keys}" }
            return null
        }
        return Lesson(
            key = key,
            recommendedDiscount = recommended,
            evidenceCount = evidence,
            avgProfitAdvantagePct = row.number("avg_profit_advantage_pct") ?: BigDecimal.ZERO,
            confidence = row.number("confidence") ?: BigDecimal.ZERO,
            rationale = row["rationale"]?.asText("").orEmpty(),
        )
    }

    private companion object {
        private val log: Logger = Logger.getLogger(XmemoryLearningMemory::class.java.name)

        @Suppress("unused")
        private val SCOPES = LessonScope.entries

        /**
         * xmemory reports both of these as HTTP 400 `VALIDATION_ERROR`, which is also what a
         * genuinely malformed call returns, so they are recognised by their message text. Each
         * matches one phrase and nothing else: broadening either would start swallowing real
         * rejections, and a swallowed write is a run that learns nothing while reporting success.
         */
        private val NO_SUCH_KEY: (String) -> Boolean = { it.contains("matches the provided primary key") }

        private val ALREADY_EXISTS: (String) -> Boolean = { it.contains("already exists") }
    }
}
