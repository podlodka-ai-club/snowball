package club.podlodka.snowball.adapter.cli

import club.podlodka.snowball.adapter.context.DeterministicContextEnricher
import club.podlodka.snowball.adapter.model.PromptBuilder
import club.podlodka.snowball.adapter.simulator.SimulationEngine
import club.podlodka.snowball.adapter.source.DatasetBaselineSource
import club.podlodka.snowball.application.LessonRanker
import club.podlodka.snowball.application.ScenarioGenerationService
import club.podlodka.snowball.domain.CaseEvidence
import club.podlodka.snowball.domain.DatasetSplit
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.Lesson
import club.podlodka.snowball.domain.LessonKey
import club.podlodka.snowball.domain.PromotionScenarioEvent
import club.podlodka.snowball.domain.SimulatorVersion
import club.podlodka.snowball.port.ScenarioPublisher
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.io.path.reader

/**
 * Prints exactly what the model was shown for one scenario, with the lessons the trained memory
 * would have supplied.
 *
 * For the demo and for arguing about a decision after the fact. Lessons are recomputed from the
 * training cases - deterministically, the same aggregation the learner runs - so this needs no
 * memory quota and reproduces what the trained instance holds. The prompt text comes from the
 * same builder the agent uses; nothing here is paraphrased.
 */
object ShowPrompt {
    @JvmStatic
    fun main(args: Array<String>) {
        val scenarioId = requireNotNull(args.firstOrNull()) { "usage: ShowPrompt <scenario_id> [fixture]" }
        val fixture = Path.of(args.getOrNull(1) ?: "src/test/resources/fixtures/baseline.csv")
        val engine = SimulationEngine()

        fun scenarios(split: DatasetSplit): List<PromotionScenarioEvent> {
            val collected = mutableListOf<PromotionScenarioEvent>()
            ScenarioGenerationService(
                baselineSource = DatasetBaselineSource { fixture.reader() },
                contextEnricher = DeterministicContextEnricher(),
                publisher = ScenarioPublisher { collected += it },
            ).generate(split)
            return collected
        }
        val training = scenarios(DatasetSplit.TRAINING)
        val target =
            requireNotNull((training + scenarios(DatasetSplit.BENCHMARK)).firstOrNull { it.scenarioId == scenarioId }) {
                "no scenario $scenarioId"
            }

        fun profits(event: PromotionScenarioEvent): Map<Discount, BigDecimal> =
            Discount.entries.associateWith { engine.simulate(event.scenarioId, event.scenario, it).grossProfit }

        fun best(p: Map<Discount, BigDecimal>): Discount =
            Discount.entries
                .sortedWith(compareByDescending<Discount> { p.getValue(it) }.thenBy { it.percent })
                .first()

        // The trained memory, rebuilt: every training case in every bucket it supports.
        val byBucket = mutableMapOf<LessonKey, MutableList<CaseEvidence>>()
        training.forEach { event ->
            val p = profits(event)
            val case = CaseEvidence("CASE-${SimulatorVersion.V1.wire}-${event.scenarioId}", p, best(p))
            LessonKey.bucketsFor(event.scenario).forEach { byBucket.getOrPut(it) { mutableListOf() } += case }
        }
        val lessons = byBucket.mapValues { (key, cases) -> Lesson.from(key, cases) }

        // What recall does: strictest bucket per scope that has an answer, then the ranker.
        val recalled =
            LessonKey
                .bucketsFor(target.scenario)
                .groupBy { it.scope }
                .values
                .mapNotNull { buckets -> buckets.firstNotNullOfOrNull { lessons[it] } }
        val eligible = LessonRanker.eligible(target.scenario, recalled)

        println("=== scenario ===")
        println("${target.scenarioId}: ${target.scenario.skuName ?: target.scenario.skuId}, ${target.scenario.date}")
        println("=== system prompt ===")
        println(PromptBuilder.SYSTEM)
        println("=== user prompt, no memory ===")
        println(PromptBuilder.user(target.scenario, emptyList()))
        println("=== user prompt, trained memory (${eligible.size} lessons) ===")
        println(PromptBuilder.user(target.scenario, eligible))
        val truth = profits(target)
        println("=== oracle (never shown to the agent) ===")
        Discount.entries.forEach {
            println("  ${it.percent}% -> ${truth.getValue(it)}${if (it == best(truth)) "  <- best" else ""}")
        }
    }
}
