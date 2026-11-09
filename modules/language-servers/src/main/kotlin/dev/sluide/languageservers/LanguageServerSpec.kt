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

    /**
     * Whether [name] may name a symbol in this language, for rename (ADR 0149): servers answer invalid names too. By
     * default any non-empty name without whitespace or control characters.
     */
    fun isIdentifier(name: String): Boolean = name.isNotEmpty() && name.none { it.isWhitespace() || it.isISOControl() }

    /**
     * The workspace folder that serves [file], a file inside the window's [projectFolder] (ADR 0151): the session adds
     * it to the server's workspace folders (`workspace/didChangeWorkspaceFolders`) before the file's `didOpen`, once.
     * `null`, the default, means the window folder serves it, which is always the first workspace folder; so does a
     * folder outside [projectFolder]. Called on the session's folder worker, never on the FX thread, when the file
     * opens or moves and when [affectsWorkspaceFolders] names a change above it; may look at a few files, keep it
     * quick: the file's `didOpen` waits for the answer.
     */
    fun workspaceFolder(
        file: Path,
        projectFolder: Path,
    ): Path? = null

    /**
     * Folders whose entries the session watches (not their subfolders) for the workspace [folder], which may lie
     * outside the project watch (a venv's `site-packages`): a change there refreshes [folder] on the server (removed,
     * then added) and re-opens its documents. Asked for every workspace folder, the window folder too, again after
     * each refresh and after each re-resolution ([affectsWorkspaceFolders]) of documents it serves: an answer other
     * than the one watched (a venv that appeared, went or was replaced) refreshes the folder too, so answer the same
     * list for the same state. On the session's folder worker. Folders that do not exist (yet) are fine. None by
     * default.
     */
    fun watchedFolders(folder: Path): List<Path> = emptyList()

    /**
     * Whether [change], a path the project watch or a caller reports as created, changed or deleted (a hidden or
     * excluded folder such as `.venv` too, when it appears in or goes from a watched folder), may change the
     * [workspaceFolder] of the open files below its parent folder or what [watchedFolders] answers for their folders.
     * It only makes the session ask those again, once a second passed without further such changes there; nothing is
     * sent to the server unless an answer changed. So it may be broad (any folder): it is called on the FX thread for
     * every change, so decide by the path alone and keep it cheap. `false` by default.
     */
    fun affectsWorkspaceFolders(change: Path): Boolean = false

    /**
     * What the editor's status line says about the workspace [folder] of a file (ADR 0151); `null` for the window
     * folder or a file served by it ("native"). `null`, the default, shows nothing. Not called by the session; called on the FX thread for each bound
     * editor after every workspace change: keep it to a few small reads.
     */
    fun workspaceStatus(
        folder: Path?,
        projectFolder: Path,
    ): WorkspaceStatus? = null

    /**
     * A problem of [file]'s environment the editor shows as a notice (a project without a virtual environment), or
     * `null`, the default, for none. Not called by the session; called on the FX thread for each bound editor after
     * every workspace change: keep it to a few small reads.
     */
    fun environmentProblem(
        file: Path,
        projectFolder: Path,
    ): EnvironmentProblem? = null
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
