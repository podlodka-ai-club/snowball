package club.podlodka.snowball.adapter.memory

import club.podlodka.snowball.config.XmemoryConfig
import club.podlodka.snowball.domain.ContractJson
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** xmemory refused the call, or accepted it and reported an error inside the envelope. */
class XmemoryError(
    message: String,
    /** The `message` text of every error xmemory reported, in the order it reported them. */
    val reasons: List<String> = emptyList(),
) : IllegalStateException(message)

/**
 * The transport half of the xmemory adapter: envelope, auth, error surfacing.
 *
 * Split from the memory implementation so the learning logic can be tested against a local stub
 * server rather than against the real service - the tests must not spend quota, and a quota that
 * one overnight run can exhaust is not something to spend on assertions.
 */
class XmemoryHttp(
    private val config: XmemoryConfig,
    private val client: HttpClient = HttpClient.newHttpClient(),
) {
    /**
     * Fails unless the configured instance exists and answers.
     *
     * A run whose memory is unreachable does not stop - the agent logs the outage and decides
     * without lessons, which is right for one flaky call and wrong for a whole benchmark: a
     * mistyped instance id produced fifty perfectly ordinary-looking measurements of an arm that
     * had no memory at all. Checking once up front makes that failure loud instead of plausible.
     */
    fun requireInstance() {
        val request =
            HttpRequest
                .newBuilder(URI.create("${config.baseUrl}/instances/${config.instanceId}"))
                .timeout(config.requestTimeout)
                .header("Authorization", "Bearer ${config.apiKey}")
                .GET()
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw XmemoryError(
                "xmemory instance ${config.instanceId} is not usable " +
                    "(HTTP ${response.statusCode()}): ${response.body().take(200)}",
            )
        }
    }

    /**
     * Posts one call and returns its first result item.
     *
     * [tolerate] decides which reported errors are outcomes rather than failures. xmemory answers
     * "there is no such record" and "that record already exists" as HTTP 400 with a
     * `VALIDATION_ERROR`, so a caller asking whether a lesson exists cannot distinguish an empty
     * answer from a broken one without this. When every reported error is tolerated the call
     * returns null; if even one is not, it throws, because a partially applied batch is a failure.
     */
    fun post(
        path: String,
        body: ObjectNode,
        tolerate: (String) -> Boolean = { false },
    ): JsonNode? {
        val request =
            HttpRequest
                .newBuilder(URI.create("${config.baseUrl}/instances/${config.instanceId}$path"))
                .timeout(config.requestTimeout)
                .header("Authorization", "Bearer ${config.apiKey}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ContractJson.mapper.writeValueAsString(body)))
                .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 402) {
            throw XmemoryError("xmemory quota exhausted (402); this does not resolve by retrying: ${response.body()}")
        }

        val envelope = runCatching { ContractJson.mapper.readTree(response.body()) }.getOrNull()
        val reasons =
            envelope
                ?.path("errors")
                ?.takeIf { it.isArray }
                ?.map { it.path("message").asText("") }
                .orEmpty()

        if (response.statusCode() !in 200..299) {
            if (reasons.isNotEmpty() && reasons.all(tolerate)) {
                return null
            }
            throw XmemoryError("xmemory ${response.statusCode()} on $path: ${response.body()}", reasons)
        }
        if (reasons.isNotEmpty()) {
            if (reasons.all(tolerate)) {
                return null
            }
            throw XmemoryError("xmemory rejected $path: ${response.body()}", reasons)
        }
        return envelope?.path("items")?.firstOrNull() ?: ContractJson.mapper.createObjectNode()
    }
}
