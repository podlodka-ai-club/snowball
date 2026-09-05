package club.podlodka.snowball.port

import club.podlodka.snowball.domain.BaselineRecord
import club.podlodka.snowball.domain.SourceRejection

/**
 * What one read of the baseline source produced: the rows it could map, and the ones it refused.
 *
 * A bad row must not cost the whole batch. `docs/scenario-generator/README.md` requires an invalid
 * source record to be rejected and logged while the rest of the batch continues, so rejections are
 * returned alongside the records rather than thrown.
 */
data class BaselineLoad(
    val records: List<BaselineRecord>,
    val rejections: List<SourceRejection>,
)

/**
 * Supplies normalized baseline rows. Whether they come from a fixture, SAP, or a database is the
 * adapter's business; nothing downstream may depend on the answer.
 */
fun interface BaselineSource {
    fun load(): BaselineLoad
}
