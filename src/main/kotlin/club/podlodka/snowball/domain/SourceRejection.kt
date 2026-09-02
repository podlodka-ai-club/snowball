package club.podlodka.snowball.domain

/**
 * One refused row, carrying enough to answer "where did this come from" without reading the CSV.
 *
 * `docs/scenario-generator/README.md` asks for `scenario_id`, `source.type` and `source.reference`
 * in the structured logs; `scenarioId` is null when the row was refused before an identity could
 * be built.
 */
data class SourceRejection(
    val sourceType: String,
    val sourceReference: String?,
    val scenarioId: String?,
    val reason: String,
) {
    override fun toString(): String =
        "rejected source_type=$sourceType source_reference=${sourceReference ?: "-"} " +
            "scenario_id=${scenarioId ?: "-"} reason=$reason"
}
