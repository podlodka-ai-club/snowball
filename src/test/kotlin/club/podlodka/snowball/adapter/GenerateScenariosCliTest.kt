package club.podlodka.snowball.adapter

import club.podlodka.snowball.adapter.cli.CliOptions
import club.podlodka.snowball.adapter.cli.runCli
import club.podlodka.snowball.domain.CommittedDocs
import club.podlodka.snowball.domain.ContractJson
import club.podlodka.snowball.domain.DatasetSplit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class GenerateScenariosCliTest {
    private val fixture = "src/test/resources/fixtures/baseline.csv"

    private data class Run(
        val code: Int,
        val out: String,
        val err: String,
    ) {
        val lines: List<String> get() = out.lines().filter { it.isNotBlank() }
    }

    private fun run(vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(arrayOf(*args), PrintStream(out), PrintStream(err))
        return Run(code, out.toString(), err.toString())
    }

    @Test
    fun `values are accepted both inline and separated`() {
        assertThat(CliOptions.parse(arrayOf("--split=benchmark")).split).isEqualTo(DatasetSplit.BENCHMARK)
        assertThat(CliOptions.parse(arrayOf("--split", "benchmark")).split).isEqualTo(DatasetSplit.BENCHMARK)
        assertThat(CliOptions.parse(arrayOf("--limit=5")).limit).isEqualTo(5)
        assertThat(CliOptions.parse(arrayOf("--split=all")).split).isNull()
    }

    @Test
    fun `a bad argument is refused rather than ignored`() {
        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { CliOptions.parse(arrayOf("--nope")) }
        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { CliOptions.parse(arrayOf("--limit=0")) }
        assertThatExceptionOfType(IllegalArgumentException::class.java)
            .isThrownBy { CliOptions.parse(arrayOf("--fixture")) }
    }

    @Test
    fun `an unusable invocation exits non-zero and explains itself`() {
        val run = run("--nope")

        assertThat(run.code).isEqualTo(2)
        assertThat(run.out).isEmpty()
        assertThat(run.err).contains("unknown argument", "Usage")
    }

    @Test
    fun `what it prints is valid against the committed schema`() {
        val run = run("--fixture", fixture, "--split=benchmark", "--limit=3")

        assertThat(run.code).isZero()
        assertThat(run.lines).hasSize(3)
        run.lines.forEach { line ->
            val document = ContractJson.mapper.readTree(line)
            assertThat(CommittedDocs.validate(CommittedDocs.SCENARIO_SCHEMA, document))
                .describedAs("printed scenario %s", document.get("scenario_id"))
                .isEmpty()
        }
    }

    @Test
    fun `the report goes to stderr so stdout stays pipeable`() {
        val run = run("--fixture", fixture, "--split=training", "--limit=2")

        assertThat(run.lines).hasSize(2)
        assertThat(run.out).doesNotContain("generated=")
        assertThat(run.err).contains("generated=250", "printed=2")
    }

    @Test
    fun `a missing fixture fails without printing scenarios`() {
        val run = run("--fixture", "/nonexistent/baseline.csv")

        assertThat(run.code).isEqualTo(1)
        assertThat(run.out).isEmpty()
        assertThat(run.err).contains("generation failed")
    }

    @Test
    fun `help exits cleanly`() {
        val run = run("--help")

        assertThat(run.code).isZero()
        assertThat(run.err).contains("Usage")
        assertThat(run.out).isEmpty()
    }
}
