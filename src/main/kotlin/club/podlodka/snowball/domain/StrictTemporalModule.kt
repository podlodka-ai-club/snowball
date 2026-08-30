package club.podlodka.snowball.domain

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.module.SimpleModule
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Restricts `date` and `date-time` fields to the JSON strings the schemas declare.
 *
 * The stock java-time deserializers read a number as an epoch timestamp and an array as a
 * date triple, and they do so before any coercion configuration is consulted - a per-type
 * `CoercionAction.Fail` does not reach them. Left alone, `"generated_at": 0` would silently parse
 * as `1970-01-01T00:00:00Z`, which is exactly the kind of quiet corruption a contract layer exists
 * to stop.
 */
internal class StrictTemporalModule : SimpleModule() {
    init {
        addDeserializer(OffsetDateTime::class.java, StrictOffsetDateTimeDeserializer())
        addDeserializer(LocalDate::class.java, StrictLocalDateDeserializer())
    }
}

private fun requireStringToken(
    parser: JsonParser,
    targetType: Class<*>,
    schemaType: String,
) {
    if (parser.currentToken() != JsonToken.VALUE_STRING) {
        throw MismatchedInputException.from(
            parser,
            targetType,
            "a $schemaType must be a JSON string, was ${parser.currentToken()}",
        )
    }
}

private class StrictOffsetDateTimeDeserializer : JsonDeserializer<OffsetDateTime>() {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): OffsetDateTime {
        requireStringToken(parser, OffsetDateTime::class.java, "date-time")
        return OffsetDateTime.parse(parser.text)
    }
}

private class StrictLocalDateDeserializer : JsonDeserializer<LocalDate>() {
    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): LocalDate {
        requireStringToken(parser, LocalDate::class.java, "date")
        return LocalDate.parse(parser.text)
    }
}
