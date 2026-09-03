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
    fun post(
        path: String,
        body: ObjectNode,
    ): JsonNode {
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
        if (response.statusCode() !in 200..299) {
            throw XmemoryError("xmemory ${response.statusCode()} on $path: ${response.body()}")
        }

        val envelope = ContractJson.mapper.readTree(response.body())
        val errors = envelope.path("errors")
        if (errors.isArray && !errors.isEmpty) {
            throw XmemoryError("xmemory rejected $path: $errors")
        }
        return envelope.path("items").firstOrNull() ?: ContractJson.mapper.createObjectNode()
    }
}
