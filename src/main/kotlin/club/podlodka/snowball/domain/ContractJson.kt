package club.podlodka.snowball.domain

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.cfg.CoercionAction
import com.fasterxml.jackson.databind.cfg.CoercionInputShape
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.type.LogicalType
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule

/**
 * The single JSON mapper for the committed v1 contracts under `docs/`.
 *
 * Jackson's defaults are considerably more forgiving than JSON Schema, and every one of the
 * loopholes below was found by feeding a mutated committed example through the mapper rather than
 * by reading the documentation. Each is closed explicitly:
 *
 * - a number or boolean would be read into a `string` field as its text form;
 * - a number would be read as an enum ordinal, turning `"stock_level": 1` into `HIGH`;
 * - a number or array would be read as a date or timestamp;
 * - an explicit `null` would become `0` or `0.0` in a required numeric field, because primitives
 *   are defaulted before the constructor ever sees them;
 * - an explicit `null` would be accepted in an optional field the schema only permits to be absent;
 * - a non-UTC offset would be normalised to `Z`, changing the document on the way back out;
 * - a JSON number would be narrowed to `Double` before anything could compare it.
 *
 * Unknown properties are rejected by Jackson's own default, which matches the
 * `additionalProperties: false` every contract declares.
 *
 * One known deviation remains, in the strict direction: JSON Schema defines `integer` by
 * mathematical value, so `320.0` is a valid `stock`. This mapper rejects it and accepts only the
 * canonical `320`. Producing the non-canonical form would be a deliberate act; accepting `1.5` as
 * `1` would be a silent corruption, and that trade is worth making in this direction.
 */
object ContractJson {
    val mapper: ObjectMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .addModule(JavaTimeModule())
            .addModule(StrictTemporalModule())
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .withCoercionConfig(LogicalType.Textual) { config ->
                config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail)
            }.defaultPropertyInclusion(
                JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.USE_DEFAULTS),
            ).build()
}
