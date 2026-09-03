package club.podlodka.snowball.adapter.cli

import club.podlodka.snowball.adapter.context.DeterministicContextEnricher
import club.podlodka.snowball.adapter.memory.EvaluatingOutcomeSink
import club.podlodka.snowball.adapter.memory.InMemoryDecisionJournal
import club.podlodka.snowball.adapter.memory.InMemoryLearningMemory
import club.podlodka.snowball.adapter.model.OpenAiCompatibleDecisionModel
import club.podlodka.snowball.adapter.simulator.SimulationEngine
import club.podlodka.snowball.adapter.source.DatasetBaselineSource
import club.podlodka.snowball.application.DecisionSource
import club.podlodka.snowball.application.PromotionDecisionService
import club.podlodka.snowball.application.PromotionEvaluator
import club.podlodka.snowball.application.ScenarioGenerationService
import club.podlodka.snowball.application.SimulationService
import club.podlodka.snowball.domain.DatasetSplit
import club.podlodka.snowball.port.DecisionSink
import club.podlodka.snowball.port.LearningMemory
import club.podlodka.snowball.port.ScenarioPublisher
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import kotlin.io.path.reader

/** What one pass over a scenario set produced. */
data class RunSummary(
    val scenarios: Int,
    val totalRegret: BigDecimal,
    val optimalChoices: Int,
    val fallbacks: Int,
    val lessonsAfter: Int,
) {
    val meanRegret: BigDecimal
        get() =
            if (scenarios ==
                0
            ) {
                BigDecimal.ZERO
            } else {
                totalRegret.divide(BigDecimal(scenarios), 4, RoundingMode.HALF_UP)
            }

    val optimalRate: BigDecimal
        get() =
            if (scenarios == 0) {
                BigDecimal.ZERO
            } else {
                BigDecimal(optimalChoices * 100).divide(BigDecimal(scenarios), 1, RoundingMode.HALF_UP)
            }
}

/**
 * The whole loop in one place: scenarios in, decisions taken with whatever memory holds, ground
 * truth simulated, evidence learned.
 *
 * Wiring lives here rather than in any component, and the wiring is what enforces the boundary
 * that no type signature can: the evaluator is handed the simulation, the agent is not.
 */
class RunLoop(
    private val fixture: Path,
    private val memory: LearningMemory,
    private val modelBaseUrl: String,
    private val modelId: String,
    private val learningEnabled: Boolean = true,
) {
    fun run(
        split: DatasetSplit?,
        limit: Int?,
    ): RunSummary {
        val engine = SimulationEngine()
        val evaluatingSink = EvaluatingOutcomeSink(PromotionEvaluator(engine, memory, learningEnabled))
        val simulator = SimulationService(engine, evaluatingSink)
        val agent =
            PromotionDecisionService(
                memory = memory,
                model = OpenAiCompatibleDecisionModel(modelBaseUrl, modelId),
                journal = InMemoryDecisionJournal(),
                decisions = DecisionSink { simulator.simulate(it) },
            )

        var handled = 0
        var fallbacks = 0
        val publisher =
            ScenarioPublisher { scenario ->
                if (limit == null || handled < limit) {
                    handled += 1
                    if (agent.decide(scenario).source == DecisionSource.FALLBACK) fallbacks += 1
                }
            }

        ScenarioGenerationService(
            baselineSource = DatasetBaselineSource { fixture.reader() },
            contextEnricher = DeterministicContextEnricher(),
            publisher = publisher,
        ).generate(split)

        val results = evaluatingSink.results
        return RunSummary(
            scenarios = results.size,
            totalRegret = results.fold(BigDecimal.ZERO) { sum, it -> sum.add(it.case.regret) },
            optimalChoices = results.count { it.case.chosenDiscount == it.case.bestDiscount },
            fallbacks = fallbacks,
            lessonsAfter = (memory as? InMemoryLearningMemory)?.allLessons?.size ?: 0,
        )
    }
}
