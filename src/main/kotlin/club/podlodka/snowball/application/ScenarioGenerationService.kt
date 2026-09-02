package club.podlodka.snowball.application

import club.podlodka.snowball.config.MarketConfig
import club.podlodka.snowball.domain.BaselineRecord
import club.podlodka.snowball.domain.ContractValidator
import club.podlodka.snowball.domain.DatasetSplit
import club.podlodka.snowball.domain.PromotionScenario
import club.podlodka.snowball.domain.PromotionScenarioEvent
import club.podlodka.snowball.domain.ScenarioSource
import club.podlodka.snowball.domain.SourceRejection
import club.podlodka.snowball.port.BaselineSource
import club.podlodka.snowball.port.ContextEnricher
import club.podlodka.snowball.port.ScenarioPublisher
import java.time.Clock
import java.time.OffsetDateTime

/** What one generation cycle produced, including what it refused and why. */
data class GenerationReport(
    val published: Int,
    val rejected: List<SourceRejection>,
) {
    val hasRejections: Boolean get() = rejected.isNotEmpty()
}

/**
 * One generation cycle, shared by every trigger.
 *
 * Scheduled and manual runs go through this same service: two entry points that each map rows
 * their own way is how the two drift apart.
 */
class ScenarioGenerationService(
    private val baselineSource: BaselineSource,
    private val contextEnricher: ContextEnricher,
    private val publisher: ScenarioPublisher,
    private val market: MarketConfig = MarketConfig.LONDON_CENTRAL,
    private val sourceType: String = "dataset",
    private val clock: Clock = Clock.systemUTC(),
    /**
     * The contract check applied before handoff. Injected so a test can prove the service actually
     * consults it: the models are strict enough that a generated event is valid anyway, so a test
     * that only inspects what was published stays green even if this check is deleted. The check
     * exists for the case the models and the committed schemas drift apart.
     */
    private val validate: (PromotionScenarioEvent) -> Unit = ContractValidator::validateScenario,
) {
    fun generate(split: DatasetSplit? = null): GenerationReport {
        val load =
            try {
                baselineSource.load()
            } catch (failure: IllegalArgumentException) {
                return GenerationReport(
                    published = 0,
                    rejected = listOf(SourceRejection(sourceType, null, null, "source unusable: ${failure.message}")),
                )
            }
        val rejected = load.rejections.toMutableList()
        var published = 0

        // A different scenario must get a different identity. The identity is built from the
        // scenario facts, so two rows describing the same day, market and SKU from different
        // source records would collide - and the Promotion Agent, which deduplicates on this id,
        // would silently drop one. Caught here rather than discovered as a missing scenario.
        val identities = mutableMapOf<String, String>()

        load.records
            .filter { split == null || it.split == split }
            .forEach { record ->
                val scenarioId = scenarioId(record)
                val seen = identities.putIfAbsent(scenarioId, record.sourceReference)
                if (seen != null && seen != record.sourceReference) {
                    rejected +=
                        SourceRejection(
                            sourceType,
                            record.sourceReference,
                            scenarioId,
                            "identity collides with source_reference $seen",
                        )
                    return@forEach
                }
                try {
                    val event = toEvent(record, scenarioId)
                    validate(event)
                    publisher.publish(event)
                    published += 1
                } catch (failure: IllegalArgumentException) {
                    rejected +=
                        SourceRejection(sourceType, record.sourceReference, scenarioId, failure.message ?: "invalid")
                }
            }
        return GenerationReport(published, rejected)
    }

    private fun toEvent(
        record: BaselineRecord,
        scenarioId: String,
    ): PromotionScenarioEvent {
        val context = contextEnricher.enrich(record)
        return PromotionScenarioEvent(
            scenarioId = scenarioId,
            generatedAt = OffsetDateTime.now(clock),
            source = ScenarioSource(type = sourceType, reference = record.sourceReference),
            scenario =
                PromotionScenario(
                    date = record.date,
                    storeId = market.storeId,
                    skuId = record.skuId,
                    category = record.category,
                    price = record.price,
                    cost = record.cost,
                    stock = record.stock,
                    baselineSales = record.baselineSales,
                    stockLevel = record.stockLevel,
                    dayType = context.dayType,
                    weather = context.weather,
                    eventType = context.eventType,
                    storeName = market.storeName,
                    skuName = record.skuName,
                    temperatureC = context.temperatureC,
                    eventNote = context.eventNote,
                ),
        )
    }

    /**
     * Identity is a function of the scenario facts only - it must survive a retry, a restart, and a
     * rerun next week, which is why nothing here reads the clock.
     *
     * The shape follows the committed example, `SCN-<date>-<store>-<sku>`, rather than the longer
     * form sketched in the guide, which also folds in `source_reference`. Readability wins here
     * because the guide explicitly allows shortening; what the guide actually requires is that
     * different scenarios get different identities, and that is enforced above instead of being
     * assumed.
     */
    private fun scenarioId(record: BaselineRecord): String =
        "SCN-${record.date.toString().replace("-", "")}-${market.storeId}-${record.skuId}"
}
