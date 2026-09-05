package club.podlodka.snowball.config

import java.time.Duration

/**
 * Where the durable memory lives and how patiently to wait for it.
 *
 * The key is read from the environment and never from a committed file. Timeouts are generous by
 * web standards because a measured cold read took twenty-odd seconds; see `GOTCHAS.md`.
 */
data class XmemoryConfig(
    val baseUrl: String,
    val instanceId: String,
    val apiKey: String,
    val requestTimeout: Duration = Duration.ofSeconds(60),
) {
    init {
        require(baseUrl.isNotEmpty()) { "XMEM_BASE_URL must not be empty" }
        require(instanceId.isNotEmpty()) { "XMEM_INSTANCE_ID must not be empty" }
        require(apiKey.isNotEmpty()) { "XMEM_API_KEY must not be empty" }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.xmemory.ai"

        /** Reads configuration from the environment, naming precisely what is missing. */
        fun fromEnvironment(environment: (String) -> String? = System::getenv): XmemoryConfig {
            val missing = mutableListOf<String>()

            fun required(name: String): String =
                environment(name)?.takeIf { it.isNotBlank() } ?: "".also { missing += name }

            val instanceId = required("XMEM_INSTANCE_ID")
            val apiKey = required("XMEM_API_KEY")
            require(missing.isEmpty()) { "missing xmemory configuration: ${missing.joinToString(", ")}" }
            return XmemoryConfig(
                baseUrl = environment("XMEM_BASE_URL")?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL,
                instanceId = instanceId,
                apiKey = apiKey,
            )
        }
    }
}
