package dev.sluide.secrets

import java.time.Duration

/**
 * The key in the Secret Service through `secret-tool` (libsecret-tools; ADR 0122): an item with [label] and
 * [attributes], `secret-tool lookup`, `store --label` and `clear` run as [command], found on [path]. The key goes
 * through stdin and stdout, never into the arguments. Exit 1 with empty stdout and stderr is [KeyRead.Missing]; each
 * call ends after [timeout] (an unlock prompt may be open). Without the command, [unavailableReason] is
 * "needs secret-tool". Tests pass their own attributes, or a fake command on their own [path].
 *
 * Stub until the backend is implemented: nothing is stored.
 */
internal class SecretToolBackend(
    private val attributes: Map<String, String> = mapOf("application" to "sluide", "name" to "safe-storage"),
    private val label: String = "Thoreau Safe Storage",
    private val command: String = "secret-tool",
    private val timeout: Duration = Duration.ofSeconds(120),
    private val path: String? = System.getenv("PATH"),
) : KeyBackend {
    override val storeName = "Secret Service (secret-tool)"

    override fun unavailableReason(): String? = NOT_IMPLEMENTED

    override fun read(): KeyRead = KeyRead.Failed(NOT_IMPLEMENTED)

    override fun create(key: ByteArray): KeyCreate = KeyCreate.Failed(NOT_IMPLEMENTED)

    override fun delete() = false

    private companion object {
        const val NOT_IMPLEMENTED = "not implemented yet"
    }
}
