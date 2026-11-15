package dev.sluide.languageservers

import com.google.gson.JsonElement
import org.eclipse.lsp4j.Diagnostic
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * What a spec adds to its session ([LanguageServerSpec.sessionExtension], ADR 0179): server notifications lsp4j does not
 * know (jdtls's `language/status`), progress, folder announcements and the server's life. Made at the session's first
 * start that has a window folder; a start with another window folder [close]s it and makes a new one; closed with the
 * session. Every call comes on the FX thread; one that throws is logged and the session goes on. Nothing of an earlier
 * server arrives once a newer one started.
 */
interface SessionExtension {
    /**
     * Server notifications (method names) to route to [onNotification], e.g. "language/status": only methods lsp4j's
     * `LanguageClient` does not handle; others named here are logged and stay with the session.
     */
    val notifications: Set<String>

    /** A notification of the current server whose method is in [notifications]; [params] as sent, `null` if none. */
    fun onNotification(
        method: String,
        params: JsonElement?,
    ) {}

    /** Every `$/progress` value of the current server, in addition to today's handling; a number token as text. */
    fun onProgress(
        token: String,
        value: JsonElement,
    ) {}

    /** A folder reached the current server (added); after the notification was written. */
    fun onFolderAdded(folder: Path) {}

    /** A folder was removed from the current server; after the notification was written. */
    fun onFolderRemoved(folder: Path) {}

    /** A new server is initialized (same FX step as the session's own onConnected listeners). */
    fun onConnected() {}

    /** The current server stopped, crashed or is restarting (before a new one connects). */
    fun onStopped() {}

    /** The session closes, or a start with another window folder replaces this extension; called once. */
    fun close() {}
}

/**
 * What a [SessionExtension] may do with its session. FX thread only. Requests and notifications go to the running
 * server only, in order with the session's own on its outbound thread; none starts a server. Values are JSON as for
 * [LanguageServerSpec.configuration] (ADR 0142): another type is logged and sent as `null`.
 */
interface SessionAccess {
    /**
     * Sends `workspace/executeCommand`; the result or the failure completes on the FX thread, a
     * [LanguageServerNotRunningException] when no server runs. The result is untyped, so lsp4j reads it with Gson's
     * plain types: lists, maps with string keys, strings, `Double` numbers, booleans, `null`.
     */
    fun executeCommand(
        command: String,
        args: List<Any?>,
    ): CompletableFuture<Any?>

    /** Sends the request [method]; its raw result as on [executeCommand]. */
    fun request(
        method: String,
        params: Any?,
    ): CompletableFuture<JsonElement?>

    /** Sends the notification [method]; dropped when no server runs. */
    fun notify(
        method: String,
        params: Any?,
    )

    /** Sends `workspace/didChangeConfiguration` with `{"settings": settings}`. */
    fun configurationChanged(settings: Any)

    /** The kept diagnostics of [file] (ADR 0160), as `LanguageServerSession.keptDiagnostics`. */
    fun keptDiagnostics(file: Path): List<Diagnostic>

    /** Refreshes a known workspace folder as ADR 0151 decision 4 does (removed, added, its documents reopened). */
    fun refreshFolder(folder: Path)

    /** Makes the session fire `workspaceChanges` (status lines and banners ask the spec again). */
    fun workspaceChanged()

    /** As the user's "Restart … Language Server". */
    fun restart()
}
