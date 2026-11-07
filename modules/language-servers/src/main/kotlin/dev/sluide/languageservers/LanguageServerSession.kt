package dev.sluide.languageservers

import dev.sluide.languageservers.connection.ClientEvents
import dev.sluide.languageservers.connection.Scheduler
import dev.sluide.languageservers.connection.ServerLifecycle
import dev.sluide.languageservers.connection.ServerTiming
import dev.sluide.languageservers.connection.SessionClient
import dev.sluide.languageservers.process.ServerStarter
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private val log = System.getLogger(LanguageServerSession::class.java.name)

/** A request found no running server: not started, starting failed, restarting or closed. */
class LanguageServerNotRunningException(
    message: String,
) : RuntimeException(message)

/**
 * The connection of one window to the server of one spec ([LanguageServers.session]). It outlives restarts: listeners
 * stay registered, requests go to whichever server runs. The server starts on [ensureStarted] or with the first
 * request or notification.
 *
 * Everything sent (requests, notifications) goes out in order on one outbound thread; results, listener calls and
 * state changes arrive on the FX thread through `dispatch`. Nothing here blocks the calling thread.
 */
class LanguageServerSession internal constructor(
    val spec: LanguageServerSpec,
    projectFolder: () -> Path?,
    trustGate: TrustGate,
    private val dispatch: (Runnable) -> Unit,
    starter: ServerStarter,
    scheduler: Scheduler,
    timing: ServerTiming,
    nanoTime: () -> Long,
) {
    private val stateWrapper = ReadOnlyObjectWrapper<ServerState>(ServerState.Stopped)
    private val diagnosticsListeners =
        ConcurrentHashMap<
            String,
            CopyOnWriteArrayList<
                (
                    PublishDiagnosticsParams,
                ) -> Unit,
            >,
        >()
    private val refreshListeners = ConcurrentHashMap<RefreshKind, CopyOnWriteArrayList<() -> Unit>>()
    private val featureListeners = CopyOnWriteArrayList<() -> Unit>()
    private val connectedListeners = CopyOnWriteArrayList<(LanguageServer) -> Unit>()

    private val events =
        object : ClientEvents {
            override fun configuration(item: ConfigurationItem): Any? = spec.configuration(item)

            override fun diagnostics(params: PublishDiagnosticsParams) {
                val listeners = diagnosticsListeners[LspUris.normalize(params.uri)] ?: return
                fx { listeners.forEach { it(params) } }
            }

            override fun refresh(kind: RefreshKind) {
                val listeners = refreshListeners[kind] ?: return
                fx { listeners.forEach { it() } }
            }

            override fun featuresChanged() = fx { featureListeners.forEach { it() } }
        }

    private val lifecycle =
        ServerLifecycle(
            spec = spec,
            projectFolder = projectFolder,
            trustGate = trustGate,
            starter = starter,
            scheduler = scheduler,
            timing = timing,
            nanoTime = nanoTime,
            publish = { state -> dispatch(Runnable { stateWrapper.set(state) }) },
            newClient = { root, features, name -> SessionClient(name, root, features, events) },
            connected = { server ->
                connectedListeners.forEach { it(server) }
                fx { featureListeners.forEach { it() } }
            },
        )

    /** The server's state; changes on the FX thread. */
    val state: ReadOnlyObjectProperty<ServerState> = stateWrapper.readOnlyProperty

    /** What the running server offers; a server that is not running offers nothing. */
    val features: ServerFeatures get() = lifecycle.features

    /** Whether the window closed this session; nothing arrives any more. */
    val isClosed: Boolean get() = lifecycle.isClosed

    /**
     * Starts the server unless it runs, starts or failed (only [restart] starts a failed one); also when it was
     * [ServerState.Unavailable] or [ServerState.Untrusted] before, for a server installed or trusted since.
     */
    fun ensureStarted() = lifecycle.ensureStarted(evenIfRefused = true)

    /** Stops the server and starts it again ("Restart … Language Server", after an update). */
    fun restart() = lifecycle.restart()

    /**
     * Sends the request [call] makes with the server proxy, after everything sent before. [onResult] gets the result
     * on the FX thread unless the request was cancelled or the session closed first; [onError] gets the server's error
     * ([org.eclipse.lsp4j.jsonrpc.ResponseErrorException]) or a [LanguageServerNotRunningException]. A cancelled request
     * calls neither.
     */
    fun <T> request(
        call: (LanguageServer) -> CompletableFuture<T>,
        onResult: (T) -> Unit,
        onError: (Throwable) -> Unit = { error -> log.log(System.Logger.Level.INFO, "lsp ${spec.id} request: $error") },
    ): PendingRequest {
        val pending = PendingRequest()
        lifecycle.ensureStarted(evenIfRefused = false)
        lifecycle.onServer { server ->
            if (pending.isCancelled) return@onServer
            if (server == null) {
                deliver(
                    pending,
                ) { onError(LanguageServerNotRunningException("${spec.name} language server is not running")) }
                return@onServer
            }
            val future =
                try {
                    call(server)
                } catch (e: RuntimeException) {
                    deliver(pending) { onError(e) }
                    return@onServer
                }
            if (!pending.sent(future)) return@onServer
            future.whenComplete { result, error ->
                val cause = if (error is CompletionException) error.cause ?: error else error
                when {
                    cause is CancellationException -> Unit
                    cause != null -> deliver(pending) { onError(cause) }
                    else -> deliver(pending) { onResult(result) }
                }
            }
        }
        return pending
    }

    /**
     * Sends notifications through [action] on the outbound thread, in order with requests (for `didOpen`,
     * `didChange`, `didSave`, `didClose`, `didChangeWatchedFiles`); dropped while no server runs: [onConnected]
     * re-sends the state a new server needs.
     */
    fun notify(action: (LanguageServer) -> Unit) {
        lifecycle.ensureStarted(evenIfRefused = false)
        lifecycle.onServer { server ->
            if (server == null) return@onServer
            try {
                action(server)
            } catch (e: RuntimeException) {
                log.log(System.Logger.Level.INFO, "lsp ${spec.id} notification failed: $e")
            }
        }
    }

    /**
     * Calls [listener] on the outbound thread each time a server is initialized (also after restarts), before
     * anything queued meanwhile is sent: where document sync opens the open files again.
     */
    fun onConnected(listener: (LanguageServer) -> Unit): AutoCloseable = connectedListeners.subscribe(listener)

    /** Calls [listener] on the FX thread with every `publishDiagnostics` for [file]. */
    fun onDiagnostics(
        file: Path,
        listener: (PublishDiagnosticsParams) -> Unit,
    ): AutoCloseable =
        diagnosticsListeners.computeIfAbsent(LspUris.of(file)) { CopyOnWriteArrayList() }.subscribe(listener)

    /** Calls [listener] on the FX thread when the server asks to pull [kind] again. */
    fun onRefresh(
        kind: RefreshKind,
        listener: () -> Unit,
    ): AutoCloseable = refreshListeners.computeIfAbsent(kind) { CopyOnWriteArrayList() }.subscribe(listener)

    /** Calls [listener] on the FX thread when [features] changed: a server initialized, a (un)registration. */
    fun onFeaturesChanged(listener: () -> Unit): AutoCloseable = featureListeners.subscribe(listener)

    /** Ends the server with its window; nothing is delivered afterwards. */
    internal fun close() = lifecycle.close()

    private fun deliver(
        pending: PendingRequest,
        action: () -> Unit,
    ) = fx { if (!pending.isCancelled) action() }

    /** Runs [action] on the FX thread unless the session closed by then. */
    private fun fx(action: () -> Unit) = dispatch(Runnable { if (!isClosed) action() })
}

private fun <T> MutableList<T>.subscribe(listener: T): AutoCloseable {
    add(listener)
    return AutoCloseable { remove(listener) }
}
