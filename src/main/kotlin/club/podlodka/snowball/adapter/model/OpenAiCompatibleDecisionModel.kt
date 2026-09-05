package club.podlodka.snowball.adapter.model

import club.podlodka.snowball.domain.ContractJson
import club.podlodka.snowball.domain.Discount
import club.podlodka.snowball.domain.Lesson
import club.podlodka.snowball.domain.PromotionScenario
import club.podlodka.snowball.port.DecisionModel
import club.podlodka.snowball.port.ModelDecision
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.logging.Logger

/**
 * Asks an OpenAI-compatible endpoint for a discount.
 *
 * `temperature` is zero because a run that cannot be repeated is not a result, and the model
 * version is recorded with every answer for the same reason.
 *
 * The token budget is deliberately generous. This model spends its first few hundred tokens on a
 * reasoning block, and a tight limit truncates the answer before any content is produced - the
 * response then comes back empty with `finish_reason=length`. Measured on the real prompt: 400
 * tokens yields nothing, 1200 is enough with room to spare. A truncated answer would be counted as
 * a model failure and fall back to 0%, which would quietly turn the clean-memory arm into a
 * fixed policy and inflate the very delta the experiment measures.
 */
class OpenAiCompatibleDecisionModel(
    private val baseUrl: String,
    override val modelId: String,
    private val apiKey: String? = null,
    private val maxTokens: Int = 1500,
    private val timeout: Duration = Duration.ofMinutes(2),
    private val client: HttpClient = HttpClient.newHttpClient(),
) : DecisionModel {
    override fun choose(
        scenario: PromotionScenario,
        lessons: List<Lesson>,
    ): ModelDecision? {
        val body =
            ContractJson.mapper.createObjectNode().apply {
                put("model", modelId)
                put("temperature", 0)
                put("max_tokens", maxTokens)
                set<com.fasterxml.jackson.databind.node.ArrayNode>(
                    "messages",
                    ContractJson.mapper.createArrayNode().apply {
                        add(message("system", PromptBuilder.SYSTEM))
                        add(message("user", PromptBuilder.user(scenario, lessons)))
                    },
                )
            }

        val builder =
            HttpRequest
                .newBuilder(URI.create("$baseUrl/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(ContractJson.mapper.writeValueAsString(body)))
        apiKey?.let { builder.header("Authorization", "Bearer $it") }

        val response =
            try {
                client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            } catch (failure: Exception) {
                log.warning { "decision model unreachable: ${failure.message}" }
                return null
            }
        if (response.statusCode() !in 200..299) {
            log.warning { "decision model returned ${response.statusCode()}: ${response.body().take(200)}" }
            return null
        }

        // The body is parsed defensively: a 200 whose stream was cut mid-token is not a protocol
        // error the caller should die on. One truncated answer ended a 250-scenario training run
        // at scenario 50 - a failed answer has to cost one scenario's retry, never the run.
        val parsed =
            try {
                ContractJson.mapper.readTree(response.body())
            } catch (failure: Exception) {
                log.warning { "decision model answer was not readable: ${failure.message}" }
                return null
            }
        val choice = parsed.path("choices").firstOrNull()
        val answer =
            choice
                ?.path("message")
                ?.path("content")
                ?.asText()
                .orEmpty()
        if (answer.isBlank()) {
            // Almost always the truncation case above rather than a refusal; worth its own line in
            // the log because the two need different fixes.
            log.warning {
                "decision model returned no content (finish_reason=${choice?.path("finish_reason")?.asText()}); " +
                    "the token budget may be too small for its reasoning block"
            }
            return null
        }
        val discount =
            parse(answer) ?: return null.also { log.warning { "unparseable model answer: ${answer.take(120)}" } }
        return ModelDecision(discount, modelId, answer.trim())
    }

    /**
     * Accepts the documented JSON and nothing looser than a bare allowed number - a model that
     * answers "I would suggest around 25%" has not chosen an allowed action, and guessing what it
     * meant would put an unallowed decision into the record.
     */
    private fun parse(answer: String): Discount? {
        val json = Regex("""\{[^{}]*"discount"\s*:\s*(\d+)[^{}]*}""").find(answer)
        val bare = Regex("""^\s*(\d+)\s*%?\s*$""").find(answer)
        val percent = (json?.groupValues?.get(1) ?: bare?.groupValues?.get(1))?.toIntOrNull() ?: return null
        return Discount.entries.firstOrNull { it.percent == percent }
    }

    private fun message(
        role: String,
        content: String,
    ) = ContractJson.mapper
        .createObjectNode()
        .put("role", role)
        .put("content", content)

    private companion object {
        private val log: Logger = Logger.getLogger(OpenAiCompatibleDecisionModel::class.java.name)
    }
}
