package dev.sluide.languageservers

import dev.sluide.languageservers.connection.ClientEvents
import dev.sluide.languageservers.connection.RunningServer
import dev.sluide.languageservers.connection.Scheduler
import dev.sluide.languageservers.connection.ServerLifecycle
import dev.sluide.languageservers.connection.ServerTiming
import dev.sluide.languageservers.connection.SessionClient
import dev.sluide.languageservers.files.DirectoryWatcher
import dev.sluide.languageservers.files.WatcherFilter
import dev.sluide.languageservers.process.ServerStarter
import dev.sluide.languageservers.workspace.Resolution
import dev.sluide.languageservers.workspace.Signal
import dev.sluide.languageservers.workspace.WorkspaceDocument
import dev.sluide.languageservers.workspace.WorkspaceFolders
import dev.sluide.languageservers.workspace.WorkspaceHost
import javafx.beans.Observable
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ObservableValue
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileEvent
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

private val log = System.getLogger(LanguageServerSession::class.java.name)

private typealias DiagnosticsListener = (PublishDiagnosticsParams) -> Unit

/** A request found no running server: not started, starting failed, restarting or closed. */
class LanguageServerNotRunningException(
    message: String,
) : RuntimeException(message)

/**
 * The connection of one window to the server of one spec ([LanguageServers.session]). It outlives restarts: listeners
 * stay registered, requests go to whichever server runs. The server starts on [ensureStarted] or with the first
 * request or notification; for a spec that gates documents ([LanguageServerSpec.gatesDocuments], ADR 0160) only once
 * an open document is served, and then by itself.
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
    /** This session's own thread that asks the spec for workspace folders and what to watch for them (ADR 0151). */
    folderWorker: Executor,
    /** Watches the folders the spec names per workspace folder. */
    directoryWatcher: DirectoryWatcher,
) {
    private val stateWrapper = ReadOnlyObjectWrapper<ServerState>(ServerState.Stopped)
    private val diagnosticsListeners = ConcurrentHashMap<String, CopyOnWriteArrayList<DiagnosticsListener>>()
    private val refreshListeners = ConcurrentHashMap<RefreshKind, CopyOnWriteArrayList<() -> Unit>>()
    private val featureListeners = CopyOnWriteArrayList<() -> Unit>()
    private val connectedListeners = CopyOnWriteArrayList<() -> Unit>()

    /** The connection the FX thread last heard of ([onConnected]); notifications are sent to it only. */
    @Volatile
    private var connectedEpoch: Int? = null

    private val changes = Signal()

    internal val workspace: WorkspaceFolders =
        WorkspaceFolders(
            spec = spec,
            projectFolder = projectFolder,
            worker = folderWorker,
            dispatch = { runnable -> fx { runnable.run() } },
            scheduler = scheduler,
            debounce = timing.workspaceDebounce,
            resolveWait = timing.workspaceFolderWait,
            directoryWatcher = directoryWatcher,
            host =
                object : WorkspaceHost {
                    override fun documents(): Collection<WorkspaceDocument> = documents.workspaceDocuments()

                    override fun notify(action: (LanguageServer) -> Unit) = this@LanguageServerSession.notify(action)

                    override fun onOutbound(action: (LanguageServer?) -> Unit) =
                        lifecycle.onServer { running ->
                            try {
                                action(running?.server)
                            } catch (e: RuntimeException) {
                                log.log(System.Logger.Level.INFO, "lsp ${spec.id} workspace folders: $e")
                            }
                        }

                    override fun changed() = workspaceChanged()

                    override fun watchedFilesChanged(changes: List<FileChange>) =
                        this@LanguageServerSession.watchedFilesChanged(changes)
                },
        )

    private val lifecycle: ServerLifecycle =
        ServerLifecycle(
            spec = spec,
            projectFolder = projectFolder,
            trustGate = trustGate,
            starter = starter,
            scheduler = scheduler,
            timing = timing,
            nanoTime = nanoTime,
            // A running server and its connection in one FX step: whatever sees Running and queues work on the FX
            // thread (an editor binding) finds the connection announced, not announced again after it.
            publish = { state, epoch ->
                dispatch(
                    Runnable {
                        stateWrapper.set(state)
                        if (epoch != null && !isClosed) {
                            connectedEpoch = epoch
                            connectedListeners.callEach("connected") { it() }
                            featureListeners.callEach("features") { it() }
                        }
                    },
                )
            },
            workspaceFolders = { root -> workspace.foldersFor(root) },
            newClient = { features, name, current ->
                SessionClient(name, { workspace.current }, features, events(current))
            },
        )

    /** The server's state; changes on the FX thread. */
    val state: ReadOnlyObjectProperty<ServerState> = stateWrapper.readOnlyProperty

    /** What the running server offers; a server that is not running offers nothing. */
    val features: ServerFeatures get() = lifecycle.features

    /** Whether the window closed this session; nothing arrives any more. */
    val isClosed: Boolean get() = lifecycle.isClosed

    /** The documents open on the server: `didOpen`, `didChange`, `didSave`, `didClose`, reopened on every new server. */
    val documents: DocumentSync = DocumentSync(this)

    /**
     * The workspace folder that serves the open document [file] (ADR 0151), as the spec named it
     * ([LanguageServerSpec.workspaceFolder]; the window folder itself if the spec named that); `null` for the window
     * folder, also while the spec is still being asked (the value follows once it answered) and for a file that is
     * not open (a constant `null`). Follows re-resolutions and moves while the document is open. FX thread.
     */
    fun workspaceFolderOf(file: Path): ObservableValue<Path?> = documents.folderOf(file)

    /**
     * Fires on the FX thread after a document's workspace folder changed ([workspaceFolderOf]) or its gate answer
     * changed ([gateOf]), and after a refresh of a workspace folder or a re-resolution of folders after a project
     * change finished: what an editor shows about its project (status line, environment notice) may have changed.
     */
    val workspaceChanges: Observable = changes

    /**
     * Whether the server may see the open document [file] (ADR 0160): [DocumentGate.SERVED] from the open on for a
     * spec that does not gate documents; for one that does, `null` until the spec answered, then its answer (not
     * served, without a reason, if it failed or has not answered within the folder wait, until it does). `null` for
     * a file that is not open. Changes come with [workspaceChanges]. FX thread.
     */
    fun gateOf(file: Path): DocumentGate? = documents.gateOf(file)

    /**
     * Starts the server unless it runs, starts or failed (only [restart] starts a failed one); also when it was
     * [ServerState.Unavailable] or [ServerState.Untrusted] before, for a server installed or trusted since. For a spec
     * that gates documents only while an open document is served: the session starts it itself when the first one is
     * (ADR 0160), so a window whose documents are all not served never starts the server. FX thread.
     */
    fun ensureStarted() {
        if (spec.gatesDocuments && !documents.anyServed()) return
        lifecycle.ensureStarted(evenIfRefused = true)
    }

    /** A gating spec's document became served: start the server as [ensureStarted] does. FX thread. */
    internal fun startServed() = lifecycle.ensureStarted(evenIfRefused = true)

    /**
     * Stops the server and starts it again ("Restart … Language Server", after an update). For a spec that gates
     * documents, a server not started yet (stopped, unavailable, untrusted) stays so while no open document is served
     * (ADR 0160); a started one restarts. FX thread.
     */
    fun restart() {
        val notStarted =
            state.value.let {
                it == ServerState.Stopped || it == ServerState.Unavailable || it == ServerState.Untrusted
            }
        if (!notStarted || mayStart()) lifecycle.restart()
    }

    /**
     * Sends the request [call] makes with the server proxy, after everything sent before; requests made while the
     * server starts wait for it. [call] must return the proxy's future itself: only that one sends `$/cancelRequest`
     * when cancelled, a derived future (`thenApply`) does not. [onResult] gets the result on the FX thread unless the
     * request was cancelled or the session closed first; [onError] gets the server's error
     * ([org.eclipse.lsp4j.jsonrpc.ResponseErrorException]) or a [LanguageServerNotRunningException], also when the
     * server stops (crash, restart) before it answered. A cancelled request calls neither. For a spec that gates
     * documents it starts no server while no open document is served: it then gets not running (ADR 0160). FX thread.
     */
    fun <T> request(
        call: (LanguageServer) -> CompletableFuture<T>,
        onResult: (T) -> Unit,
        onError: (Throwable) -> Unit = logRequestError,
    ): PendingRequest {
        val pending = PendingRequest()
        if (mayStart()) lifecycle.ensureStarted(evenIfRefused = false)
        lifecycle.onServer { running ->
            if (pending.isCancelled) return@onServer
            if (running == null) {
                deliver(pending) { onError(notRunning()) }
                return@onServer
            }
            val future =
                try {
                    call(running.server)
                } catch (e: RuntimeException) {
                    deliver(pending) { onError(e) }
                    return@onServer
                }
            if (!pending.sent(future)) return@onServer
            running.track(future)
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
     * Sends notifications through [action] on the outbound thread, in order with requests (`didOpen`, `didChange`,
     * `didSave`, `didClose`, `didChangeWatchedFiles`), to the server whose connection [onConnected] last announced.
     * Dropped if no server was announced yet or another one runs when it is sent: [onConnected] re-sends the state a
     * new server needs, so nothing reaches a server twice. Call it on the FX thread: the ordering against the
     * [onConnected] re-sync relies on it. Starts the server unless it runs, for a spec that gates documents only while
     * an open document is served (ADR 0160).
     */
    fun notify(action: (LanguageServer) -> Unit) = notifyRunning { action(it.server) }

    /**
     * Like [notify], with the connection's number: for what must go to the connection that got something before.
     * Without [start], a stopped server is not started (a `didClose` needs none).
     */
    internal fun notifyRunning(
        start: Boolean = true,
        action: (RunningServer) -> Unit,
    ) {
        val epoch = connectedEpoch
        if (start && mayStart()) lifecycle.ensureStarted(evenIfRefused = false)
        if (epoch == null) return
        lifecycle.onServer { running ->
            if (running == null || running.epoch != epoch) return@onServer
            try {
                action(running)
            } catch (e: RuntimeException) {
                log.log(System.Logger.Level.INFO, "lsp ${spec.id} notification failed: $e")
            }
        }
    }

    /**
     * Like [notifyRunning], with the workspace folder [folder] resolves to added on the server first unless it is
     * known (`didChangeWorkspaceFolders`): for `didOpen`. Waits for the folder on the outbound thread, in order.
     */
    internal fun notifyInFolder(
        folder: CompletableFuture<Resolution>,
        action: (RunningServer) -> Unit,
    ) = notifyRunning { running ->
        workspace.announce(folder, running.server)
        action(running)
    }

    /** Changes on disk, unfiltered: those the spec names ask the open documents' folders again. FX thread. */
    internal fun workspaceFilesChanged(changes: List<FileChange>) = workspace.filesChanged(changes)

    /**
     * Tells the running server about the [changes] its file watchers want (`workspace/didChangeWatchedFiles`); starts
     * no server, one that does not run has no watchers. FX thread.
     */
    internal fun watchedFilesChanged(changes: List<FileChange>) {
        val filter = WatcherFilter(features.fileWatchers())
        if (filter.isEmpty) return
        val wanted = changes.filter(filter::matches)
        if (wanted.isEmpty()) return
        val params = DidChangeWatchedFilesParams(wanted.map { FileEvent(LspUris.of(it.path), it.kind.type()) })
        notify { it.workspaceService.didChangeWatchedFiles(params) }
    }

    internal fun workspaceChanged() {
        if (!isClosed) changes.fire()
    }

    /**
     * Calls [listener] on the FX thread each time a server was initialized (also after restarts): where document sync
     * opens the open files again with [notify]. Notifications made before this call are dropped, those made from here
     * on reach the new server. It runs in the FX step that sets [state] to `Running`, after the state listeners: a
     * state listener's notification on seeing `Running` is still dropped.
     */
    fun onConnected(listener: () -> Unit): AutoCloseable = connectedListeners.subscribe(listener)

    /** Calls [listener] on the FX thread with every `publishDiagnostics` for [file]. */
    fun onDiagnostics(
        file: Path,
        listener: (PublishDiagnosticsParams) -> Unit,
    ): AutoCloseable = diagnosticsListeners.subscribe(LspUris.of(file), listener)

    /** Calls [listener] on the FX thread when the server asks to pull [kind] again. */
    fun onRefresh(
        kind: RefreshKind,
        listener: () -> Unit,
    ): AutoCloseable = refreshListeners.subscribe(kind, listener)

    /** Calls [listener] on the FX thread when [features] changed: a server initialized, a (un)registration. */
    fun onFeaturesChanged(listener: () -> Unit): AutoCloseable = featureListeners.subscribe(listener)

    /** Ends the server with its window; nothing is delivered afterwards. */
    internal fun close() {
        lifecycle.close()
        workspace.close()
    }

    /** What the client of one connection hands on; dropped once that connection is no longer the session's. */
    private fun events(current: () -> Boolean) =
        object : ClientEvents {
            override fun configuration(item: ConfigurationItem): Any? = spec.configuration(item)

            override fun diagnostics(params: PublishDiagnosticsParams) {
                if (!current()) return
                val listeners = diagnosticsListeners[LspUris.normalize(params.uri)] ?: return
                fx { listeners.callEach("diagnostics") { it(params) } }
            }

            override fun refresh(kind: RefreshKind) {
                if (!current()) return
                val listeners = refreshListeners[kind] ?: return
                fx { listeners.callEach("refresh") { it() } }
            }

            override fun featuresChanged() {
                if (current()) fx { featureListeners.callEach("features") { it() } }
            }
        }

    /** Whether something sent may start the server: always, for a spec that gates documents once one is served. */
    private fun mayStart(): Boolean = !spec.gatesDocuments || documents.anyServed()

    private fun notRunning() = LanguageServerNotRunningException("${spec.name} language server is not running")

    /** The default of [request]'s onError: the failure at INFO. */
    internal val logRequestError: (Throwable) -> Unit = { error ->
        log.log(System.Logger.Level.INFO, "lsp ${spec.id} request: $error")
    }

    private fun deliver(
        pending: PendingRequest,
        action: () -> Unit,
    ) = fx { if (!pending.isCancelled) action() }

    /** Runs [action] on the FX thread unless the session closed by then. */
    internal fun fx(action: () -> Unit) = dispatch(Runnable { if (!isClosed) action() })

    /** Calls every listener; one that throws is logged and the others still run. */
    private fun <T> List<T>.callEach(
        what: String,
        call: (T) -> Unit,
    ) = forEach { listener ->
        try {
            call(listener)
        } catch (e: RuntimeException) {
            log.log(System.Logger.Level.ERROR, "lsp ${spec.id}: a $what listener failed", e)
        }
    }
}

private fun <T> MutableList<T>.subscribe(listener: T): AutoCloseable {
    add(listener)
    return AutoCloseable { remove(listener) }
}

/** Adds [listener] under [key]; closing removes it, and the key with its last listener. */
private fun <K : Any, T> ConcurrentHashMap<K, CopyOnWriteArrayList<T>>.subscribe(
    key: K,
    listener: T,
): AutoCloseable {
    compute(key) { _, listeners -> (listeners ?: CopyOnWriteArrayList()).also { it.add(listener) } }
    return AutoCloseable {
        computeIfPresent(key) { _, listeners ->
            listeners.remove(listener)
            listeners.ifEmpty { null }
        }
    }
}
