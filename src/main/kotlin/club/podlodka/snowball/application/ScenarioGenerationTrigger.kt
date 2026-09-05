package club.podlodka.snowball.application

import club.podlodka.snowball.domain.DatasetSplit

/**
 * The two ways a generation cycle starts. Both delegate to the same service, so a scheduled run
 * and a demo run cannot diverge in what they produce.
 */
class ScenarioGenerationTrigger(
    private val service: ScenarioGenerationService,
) {
    fun onSchedule(split: DatasetSplit? = null): GenerationReport = service.generate(split)

    fun runNow(split: DatasetSplit? = null): GenerationReport = service.generate(split)
}
