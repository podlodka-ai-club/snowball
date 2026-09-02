package club.podlodka.snowball.port

import club.podlodka.snowball.domain.BaselineRecord
import club.podlodka.snowball.domain.ScenarioContext

/**
 * Adds the decision context the baseline data does not carry.
 *
 * Implementations must be pure functions of the record: the same row has to enrich identically in
 * another process, on another machine, and next week, or no eval is reproducible.
 */
fun interface ContextEnricher {
    fun enrich(record: BaselineRecord): ScenarioContext
}
