package club.podlodka.snowball.domain

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule

/**
 * The single JSON mapper for the committed v1 contracts under `docs/`.
 *
 * It is configured to be exactly as strict as the schemas are. Jackson's defaults are more
 * forgiving than JSON Schema in three ways that would let an invalid document through the
 * boundary, so each is turned off explicitly:
 *
 * - scalar coercion would accept `"5.0"` where the schema says `number`;
 * - float-to-integer coercion would accept `1.5` where the schema says `integer`;
 * - a creator property would accept an explicit `null` where the schema allows the field to be
 *   absent but never to be present and null.
 *
 * Unknown properties are rejected by Jackson's own default, which matches the
 * `additionalProperties: false` every contract declares. Absent optional fields are not emitted as
 * explicit nulls, and temporal values keep their ISO-8601 text form.
 */
object ContractJson {
    val mapper: ObjectMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .addModule(JavaTimeModule())
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .defaultPropertyInclusion(
                JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.USE_DEFAULTS),
            ).build()
}
