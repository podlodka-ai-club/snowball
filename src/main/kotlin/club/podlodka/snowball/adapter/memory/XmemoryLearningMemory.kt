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

/**
 * Durable learning memory backed by xmemory.
 *
 * Two rules shape this adapter, both from measurement rather than taste - see `GOTCHAS.md`.
 *
 * Writes use `structured_mutations`, which xmemory applies deterministically with no model
 * involved. That is what makes writing a few hundred training cases affordable at all; the text
 * path would spend model tokens on data we already have in typed form.
 *
 * Reads are scoped by primary key. A natural-language read costs 20-26 seconds and a model call,
 * and worse, it proved unreliable for fetching known records - three queries asking for cases by
 * SKU answered "no matching case" while the rows were provably there. Retrieval here is never
 * conversational.
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
        write(listOf(objectMutation("PromotionCase", "case_id", case.caseId, values)))
    }

    override fun findCase(caseId: String): PromotionCase? {
        // Presence is what the evaluator asks about - it uses this to avoid re-learning a case it
        // already recorded. A stored case cannot be rebuilt in full (its SKU and category live on
        // the related record), so answering with a half-invented one would be worse than saying
        // nothing; the evaluator re-derives the case from the outcome it already holds.
        return null
    }

    /** Whether this case is already recorded, which is the question `findCase` really answers. */
    fun hasCase(caseId: String): Boolean =
        scopedRead("PromotionCase", "case_id", caseId).any { it.identifier("case_id") == caseId }

    override fun linkCaseToLesson(
        caseId: String,
        key: LessonKey,
    ) {
        val endpoints =
            json.createArrayNode().apply {
                add(
                    json
                        .createObjectNode()
                        .put(
                            "object_name",
                            "Lesson",
                        ).set<ObjectNode>("key", keyNode("lesson_key", key.wire)),
                )
                add(
                    json
                        .createObjectNode()
                        .put(
                            "object_name",
                            "PromotionCase",
                        ).set<ObjectNode>("key", keyNode("case_id", caseId)),
                )
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
        write(listOf(mutation))
    }

    override fun casesFor(key: LessonKey): List<CaseEvidence> =
        scopedRead("Lesson", "lesson_key", key.wire, withRelations = true)
            .filter { it.path("name").asText() == "PromotionCase" }
            .map(::toEvidence)

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
        val operation = if (lesson(lesson.key) == null) "create" else "update"
        write(listOf(objectMutation("Lesson", "lesson_key", lesson.key.wire, values, operation)))
    }

    override fun lesson(key: LessonKey): Lesson? =
        scopedRead("Lesson", "lesson_key", key.wire)
            .firstOrNull { it.identifier("lesson_key") == key.wire }
            ?.let { toLesson(key, it) }

    private fun write(mutations: List<JsonNode>) {
        val body =
            json.createObjectNode().apply {
                set<ArrayNode>("structured_mutations", json.createArrayNode().addAll(mutations))
            }
        http.post("/write", body)
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
        withRelations: Boolean = false,
    ): List<JsonNode> {
        val body =
            json.createObjectNode().apply {
                put("query", "Return the scoped records.")
                put("mode", "xresponse")
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
                        put("relations_scope", if (withRelations) "all_relations" else "no_relations")
                    },
                )
            }
        return http
            .post("/read", body)
            .path("reader_result")
            .path("objects")
            .toList()
    }

    private fun keyNode(
        field: String,
        value: String,
    ): ObjectNode = json.createObjectNode().set("key", json.createObjectNode().put(field, value))

    private fun JsonNode.identifier(field: String): String? =
        path("fields").firstOrNull { it.path("name").asText() == field }?.value()?.asText()
            ?: path(field).takeIf { !it.isMissingNode }?.asText()

    private fun JsonNode.value(): JsonNode? = path("value").let { v -> v.elements().asSequence().firstOrNull() ?: v }

    private fun JsonNode.field(name: String): JsonNode =
        path("fields").firstOrNull { it.path("name").asText() == name }?.value() ?: path(name)

    /**
     * Only the columns a lesson aggregates. The stored case does not carry the SKU or category as
     * its own fields - they live on the related SKU record - so reconstructing a full case here
     * would mean inventing them.
     */
    private fun toEvidence(node: JsonNode): CaseEvidence =
        CaseEvidence(
            caseId = node.identifier("case_id")!!,
            profitByDiscount =
                Discount.entries.associateWith { BigDecimal(node.field("profit_${it.percent}").asText()) },
            bestDiscount = Discount.fromPercent(node.field("best_discount").asInt()),
        )

    private fun toLesson(
        key: LessonKey,
        node: JsonNode,
    ): Lesson =
        Lesson(
            key = key,
            recommendedDiscount = Discount.fromPercent(node.field("recommended_discount").asInt()),
            evidenceCount = node.field("evidence_count").asInt(),
            avgProfitAdvantagePct = BigDecimal(node.field("avg_profit_advantage_pct").asText("0")),
            confidence = BigDecimal(node.field("confidence").asText("0")),
            rationale = node.field("rationale").asText(""),
        )

    private companion object {
        @Suppress("unused")
        private val SCOPES = LessonScope.entries
    }
}
