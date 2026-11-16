package dev.sluide.languageservers

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import dev.sluide.languageservers.connection.RunningServer
import dev.sluide.languageservers.connection.jsonTree
import dev.sluide.languageservers.connection.protocolGson
import dev.sluide.languageservers.workspace.FolderSent
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints
import org.eclipse.lsp4j.services.LanguageClient
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

private val log = System.getLogger(SessionExtensions::class.java.name)

/**
 * The [SessionExtension] of one session, if its spec has one (ADR 0179), and the [SessionAccess] it gets. It is made
 * at the first start that finds a window folder ([prepare]): the window gets its folder only after its language
 * servers were made. A start with another window folder closes it and makes one for the new folder. Every call of the
 * extension runs on the FX thread through the session's dispatch, and one that throws is logged; nothing of a server
 * reaches it once a newer one started.
 */
internal class SessionExtensions(
    private val session: LanguageServerSession,
    private val projectFolder: () -> Path?,
    private val dispatch: (Runnable) -> Unit,
) {
    private val spec = session.spec
    private val access = Access()

    /** The extension of the window folder it was made for; set on the FX thread, read on the reader thread too. */
    @Volatile
    private var current: Made? = null

    /** The window folder the spec was last asked for; FX thread. */
    private var askedFor: Path? = null

    /** The connection the extension heard of with `onConnected` and not yet with `onStopped`; FX thread. */
    @Volatile
    private var connected: Int? = null

    private val closed = AtomicBoolean()

    /** Whether the session has an extension: what feeds only it (progress) is skipped without one. Any thread. */
    val active: Boolean get() = current != null

    /**
     * A start is about to begin: makes the extension for the window folder, once per folder, closing the one of an
     * earlier folder. Nothing without a folder. FX thread, before the start's steps.
     */
    fun prepare() {
        if (closed.get()) return
        val folder =
            try {
                projectFolder()?.toAbsolutePath()?.normalize()
            } catch (e: RuntimeException) {
                log.log(System.Logger.Level.WARNING, "lsp ${spec.id}: no window folder for the session extension", e)
                null
            } ?: return
        if (folder == askedFor) return
        askedFor = folder
        current?.let { old ->
            current = null
            connected = null
            call(old.extension, "close") { it.close() }
        }
        current = create(folder)
    }

    /** The connection [epoch] runs; in the FX step that announces it, after the roots were queued. */
    fun connected(epoch: Int) {
        connected = epoch
        call("onConnected") { it.onConnected() }
    }

    /** The session's server no longer runs (stopped, crashed, restarting, failed); FX thread. */
    fun stopped() {
        if (connected == null) return
        connected = null
        call("onStopped") { it.onStopped() }
    }

    /**
     * A notification of a connection, [current] while it is the session's; whether the extension takes it (it names
     * [method], one lsp4j's `LanguageClient` does not declare), then nothing else gets it. Reader thread.
     */
    fun notification(
        method: String,
        params: Any?,
        current: () -> Boolean,
    ): Boolean {
        if (method !in this.current?.notifications.orEmpty()) return false
        val json = params as? JsonElement
        session.fx { if (current()) call("onNotification") { it.onNotification(method, json) } }
        return true
    }

    /** A `$/progress` of a connection, [current] while it is the session's. Reader thread. */
    fun progress(
        token: String,
        value: JsonElement,
        current: () -> Boolean,
    ) {
        session.fx { if (current()) call("onProgress") { it.onProgress(token, value) } }
    }

    /**
     * [folder] was sent to the server of connection [epoch] as [sent] says. Outbound thread, after the write: one
     * notification, one call, posted in the order they were written.
     */
    fun folderSent(
        epoch: Int,
        folder: Path,
        sent: FolderSent,
    ) {
        session.fx {
            if (connected != epoch) return@fx
            when (sent) {
                FolderSent.ADDED -> call("onFolderAdded") { it.onFolderAdded(folder) }
                FolderSent.REMOVED -> call("onFolderRemoved") { it.onFolderRemoved(folder) }
                FolderSent.REFRESHED -> call("onFolderRefreshed") { it.onFolderRefreshed(folder) }
            }
        }
    }

    /** The session closes: the extension is closed once, on the FX thread. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        dispatch(Runnable { call("close") { it.close() } })
    }

    private fun create(folder: Path): Made? {
        val extension =
            try {
                spec.sessionExtension(folder, access)
            } catch (e: RuntimeException) {
                log.log(System.Logger.Level.ERROR, "lsp ${spec.id}: no session extension", e)
                null
            } ?: return null
        return Made(extension, notificationsOf(extension))
    }

    /** The notifications [extension] names that lsp4j's `LanguageClient` does not handle; the others are logged. */
    private fun notificationsOf(extension: SessionExtension): Set<String> {
        val named =
            try {
                extension.notifications.toSet()
            } catch (e: RuntimeException) {
                log.log(System.Logger.Level.ERROR, "lsp ${spec.id}: the session extension names no notifications", e)
                emptySet()
            }
        val known = named.filter { it in lsp4jHandles }
        if (known.isNotEmpty()) {
            log.log(System.Logger.Level.WARNING, "lsp ${spec.id}: the session extension cannot take $known")
        }
        return named - known.toSet()
    }

    private fun call(
        what: String,
        action: (SessionExtension) -> Unit,
    ) {
        val made = current ?: return
        call(made.extension, what, action)
    }

    private fun call(
        extension: SessionExtension,
        what: String,
        action: (SessionExtension) -> Unit,
    ) {
        try {
            action(extension)
        } catch (e: RuntimeException) {
            log.log(System.Logger.Level.ERROR, "lsp ${spec.id}: the session extension failed in $what", e)
        }
    }

    /** An extension with the notifications it takes. */
    private class Made(
        val extension: SessionExtension,
        val notifications: Set<String>,
    )

    /** [value] as JSON for the server; another type is logged and sent as `null` (ADR 0142). */
    private fun json(
        value: Any?,
        what: String,
    ): JsonElement =
        try {
            jsonTree(value)
        } catch (e: IllegalArgumentException) {
            log.log(System.Logger.Level.WARNING, "lsp ${spec.id}: $what sent as null: ${e.message}")
            JsonNull.INSTANCE
        }

    private inner class Access : SessionAccess {
        override fun executeCommand(
            command: String,
            args: List<Any?>,
        ): CompletableFuture<Any?> {
            val params = ExecuteCommandParams(command, args.map { json(it, "an argument of $command") })
            return send { it.server.workspaceService.executeCommand(params) }
        }

        override fun request(
            method: String,
            params: Any?,
        ): CompletableFuture<JsonElement?> {
            val json = json(params, "the params of $method")
            return send { it.endpoint.request(method, json) }.thenApply { result ->
                result as? JsonElement ?: result?.let(protocolGson::toJsonTree)
            }
        }

        override fun notify(
            method: String,
            params: Any?,
        ) {
            val json = json(params, "the params of $method")
            session.notifyRunning(start = false) { it.endpoint.notify(method, json) }
        }

        override fun configurationChanged(settings: Any) {
            val params = DidChangeConfigurationParams(json(settings, "the settings"))
            session.notifyRunning(start = false) { it.server.workspaceService.didChangeConfiguration(params) }
        }

        override fun keptDiagnostics(file: Path): List<Diagnostic> = session.keptDiagnostics(file)

        override fun refreshFolder(folder: Path) = session.workspace.refresh(folder.toAbsolutePath().normalize())

        override fun workspaceChanged() = session.workspaceChanged()

        override fun restart() = session.restart()

        /**
         * Sends a request to the running server only; its answer or failure completes on the FX thread. Once the session
         * closed it never completes, and cancelling it does not cancel the request.
         */
        private fun send(call: (RunningServer) -> CompletableFuture<*>) =
            CompletableFuture<Any?>().also { result ->
                session.requestRunning<Any?>(
                    start = false,
                    // The proxy's own future, so that a stop fails it; its value is only read as Any?.
                    {
                        @Suppress("UNCHECKED_CAST")
                        call(it) as CompletableFuture<Any?>
                    },
                    { result.complete(it) },
                    { result.completeExceptionally(it) },
                )
            }
    }
}

/** The notifications lsp4j's client side handles itself, cancellation included: an extension cannot take them. */
private val lsp4jHandles: Set<String> =
    ServiceEndpoints.getSupportedMethods(LanguageClient::class.java).keys + MessageJsonHandler.CANCEL_METHOD.methodName
