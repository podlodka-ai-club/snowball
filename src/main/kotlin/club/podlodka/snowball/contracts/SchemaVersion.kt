package club.podlodka.snowball.contracts

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * The `schema_version` envelope constant shared by all three v1 contracts.
 *
 * Modelled as a closed enumeration so that a document claiming another version fails to parse
 * instead of being silently interpreted as v1.
 */
enum class SchemaVersion(
    @get:JsonValue val number: Int,
) {
    V1(1),
    ;

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromNumber(number: Int): SchemaVersion =
            entries.firstOrNull { it.number == number }
                ?: throw IllegalArgumentException("Unsupported schema_version: $number")
    }
}
