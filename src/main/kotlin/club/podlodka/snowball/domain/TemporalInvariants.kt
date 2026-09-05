package club.podlodka.snowball.domain

import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * The java-time types are wider than the schema formats they stand for, so a model that is legal
 * in Kotlin could still serialize JSON the committed schema rejects.
 *
 * `LocalDate.of(10000, 7, 18)` prints as `+10000-07-18`, and an offset carrying seconds prints as
 * `+01:02:03`; neither is a valid RFC 3339 `date` or `date-time`. Deserialization cannot produce
 * them from a valid document, but direct construction can, and this contract layer is worth as
 * much on the way out as on the way in.
 */
internal fun requireSchemaDate(
    value: LocalDate,
    field: String,
) {
    require(value.year in 1..9999) {
        "$field must be a four-digit year to be a valid date, was $value"
    }
}

internal fun requireSchemaDateTime(
    value: OffsetDateTime,
    field: String,
) {
    require(value.year in 1..9999) {
        "$field must be a four-digit year to be a valid date-time, was $value"
    }
    require(value.offset.totalSeconds % 60 == 0) {
        "$field must use a whole-minute UTC offset to be a valid date-time, was ${value.offset}"
    }
}
