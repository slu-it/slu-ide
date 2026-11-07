package dev.sluide.languageservers

import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ConfigurationItem
import java.nio.file.Path

/**
 * A language whose code intelligence comes from a language server: the extension point a language module implements
 * (ADR 0142). [LanguageServers] runs one server per spec and window, started when first used.
 *
 * Every function may be called off the FX thread, [configuration] on the connection's reader thread: keep them quick
 * and thread-safe.
 */
interface LanguageServerSpec {
    /** Stable technical name, used in thread names, logs and settings: `python`. */
    val id: String

    /** The name users see: `Python`. */
    val name: String

    /** Whether the server handles [file]; asked only for files inside the project folder. */
    fun matches(file: Path): Boolean

    /** The LSP language id of [file] for `didOpen`: `python`. */
    fun languageId(file: Path): String

    /** How to start the server, or `null` if it is not available (not installed); called off the FX thread. */
    fun command(): ServerCommand?

    /**
     * The `initializationOptions` of the `initialize` request for the project folder [root], a JSON value as for
     * [configuration]; another type is left out (logged).
     */
    fun initializationOptions(root: Path): Any? = null

    /**
     * The answer to one item of a `workspace/configuration` request as a JSON value: a `Map` with string keys, a
     * `List` (any collection), a `String`, `Number` or `Boolean`, `null`, or a Gson `JsonElement`, nested as needed.
     * Another type (a Jackson node, a data class) or an exception answers `null` (logged). `null` answers JSON `null`,
     * which some servers reject (Pyrefly): answer an empty map for "nothing configured" there.
     */
    fun configuration(item: ConfigurationItem): Any? = null

    /** Changes the client capabilities Thoreau declares, for a server that needs something left out or added. */
    fun adjustCapabilities(capabilities: ClientCapabilities) = Unit

    /**
     * Whether the server runs code or tools the project configures (interpreters, plugins, build scripts): then it
     * starts only once the project trust allows [trustCheckId] (ADR 0053, ADR 0142).
     */
    val runsProjectCode: Boolean get() = false

    /** The id of the `ProjectTrustCheck` that covers what the server runs; required if [runsProjectCode]. */
    val trustCheckId: String? get() = null

    /** Installs the server for the user, or `null` if Thoreau cannot (the user installs it). */
    val installer: ServerInstaller? get() = null
}

/**
 * How to start a language server: never through a shell, the [arguments] go to the process as they are.
 *
 * @property environment changes to the inherited environment: a `null` value removes the variable.
 * @property workingDirectory where the server runs; `null` is the project folder.
 */
data class ServerCommand(
    val executable: Path,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String?> = emptyMap(),
    val workingDirectory: Path? = null,
)
