package club.podlodka.snowball.agent

import club.podlodka.snowball.adapter.cli.RunLoop
import club.podlodka.snowball.adapter.memory.InMemoryLearningMemory
import club.podlodka.snowball.domain.DatasetSplit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/**
 * The loop against the real model, skipped unless one is reachable.
 *
 * Kept out of the default path by an assumption rather than by being deleted: CI has no inference
 * server, but a developer who has one should be able to see the whole thing work in one command.
 */
class LiveModelRunTest {
    private val baseUrl = System.getenv("DECISION_MODEL_BASE_URL") ?: "http://192.168.1.212:8080/v1"
    private val modelId = System.getenv("DECISION_MODEL") ?: "muse-glimmer-30b-q3"

    private fun modelReachable(): Boolean =
        try {
            val request =
                HttpRequest
                    .newBuilder(URI.create("$baseUrl/models"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()
            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode() in 200..299
        } catch (ignored: Exception) {
            false
        }

    @Test
    fun `the loop runs end to end against a real model`() {
        assumeTrue(modelReachable(), "no inference server reachable at $baseUrl")

        val memory = InMemoryLearningMemory()
        val summary =
            RunLoop(
                fixture = Path.of("src/test/resources/fixtures/baseline.csv"),
                memory = memory,
                modelBaseUrl = baseUrl,
                modelId = modelId,
            ).run(DatasetSplit.TRAINING, limit = 5)

        assertThat(summary.scenarios).isEqualTo(5)
        assertThat(summary.lessonsAfter).isPositive()
        assertThat(summary.totalRegret).isNotNull()
        println(
            "live run: scenarios=${summary.scenarios} meanRegret=${summary.meanRegret} " +
                "optimal=${summary.optimalRate}% fallbacks=${summary.fallbacks} lessons=${summary.lessonsAfter}",
        )
    }
}
