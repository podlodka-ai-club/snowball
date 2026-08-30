package club.podlodka.snowball.contracts

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule

/**
 * The single JSON mapper for the committed v1 contracts under `docs/`.
 *
 * It is configured to be as strict as the schemas are and no stricter: unknown properties are
 * rejected because every contract declares `additionalProperties: false`, absent optional fields
 * are not emitted as explicit nulls, and temporal values keep their ISO-8601 text form.
 */
object ContractJson {
    val mapper: ObjectMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .addModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .defaultPropertyInclusion(
                JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.USE_DEFAULTS),
            ).build()
}
