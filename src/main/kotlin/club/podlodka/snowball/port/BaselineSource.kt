package club.podlodka.snowball.port

import club.podlodka.snowball.domain.BaselineRecord

/**
 * Supplies normalized baseline rows. Whether they come from a fixture, SAP, or a database is the
 * adapter's business; nothing downstream may depend on the answer.
 */
fun interface BaselineSource {
    fun load(): List<BaselineRecord>
}
