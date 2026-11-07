package dev.sluide.languageservers.connection

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.languageservers.LanguageServerSpec
import dev.sluide.languageservers.LspUris
import dev.sluide.languageservers.ServerFeatures
import dev.sluide.languageservers.ServerState
import dev.sluide.languageservers.TrustGate
import dev.sluide.languageservers.process.ServerStarter
import org.eclipse.lsp4j.ClientInfo
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

private val log = System.getLogger(ServerLifecycle::class.java.name)

/** Numbers the processes of all servers for log lines and thread names: `python#3`. */
private val processNumbers = AtomicInteger()

private const val INITIALIZE_POLL_MILLIS = 100L

/**
 * The life of one spec's server in one window: lazy start (trust wait, process, `initialize`/`initialized`) on the
 * session's outbound thread, crashes restarted after [ServerTiming.backoff] and then [ServerState.Failed], user
 * restarts, and the close with `shutdown`/`exit`.
 *
 * Everything sent to the server runs on one outbound thread (`sluide-lsp-<id>`), the start too: requests and
 * notifications queued while the server starts reach it after `initialized`, in order. Each start has a generation;
 * events of an older one (its process ending after a restart) are ignored.
 */
internal class ServerLifecycle(
    private val spec: LanguageServerSpec,
    private val projectFolder: () -> Path?,
    private val trustGate: TrustGate,
    private val starter: ServerStarter,
    private val scheduler: Scheduler,
    private val timing: ServerTiming,
    private val nanoTime: () -> Long,
    /** Hands each new state on; called under the lock, in order. */
    private val publish: (ServerState) -> Unit,
    /** The client of a new connection; `current` says whether that connection is still the session's. */
    private val newClient: (
        root: Path,
        features: ServerFeatures,
        name: String,
        current: () -> Boolean,
    ) -> LanguageClient,
    /** After `initialized`, on the outbound thread: the connection number `epoch` runs. */
    private val connected: (epoch: Int) -> Unit,
) {
    private val outbound = NamedThreads.single("sluide-lsp-${spec.id}")
    private val lock = Any()
    private var state: ServerState = ServerState.Stopped
    private var generation = 0
    private var crashes = 0
    private var runningSince: Long? = null
    private var connection: LspConnection? = null
    private var running: LspConnection? = null

    /** Number of the running connection: counts the connections that reached Running. */
    private var runningEpoch = 0
    private var connects = 0
    private var retry: AutoCloseable? = null
    private var closed = false

    val isClosed: Boolean get() = synchronized(lock) { closed }

    /** The features of the running server, or none. */
    val features: ServerFeatures get() = synchronized(lock) { running?.features } ?: ServerFeatures.NONE

    /**
     * Starts the server unless it runs or starts: from [ServerState.Stopped], also from [ServerState.Unavailable] and
     * [ServerState.Untrusted] if [evenIfRefused] (installed or trusted since).
     */
    fun ensureStarted(evenIfRefused: Boolean) {
        synchronized(lock) {
            val startable =
                state == ServerState.Stopped ||
                    (evenIfRefused && (state == ServerState.Unavailable || state == ServerState.Untrusted))
            if (closed || !startable) return
            generation++
            setState(ServerState.Starting)
            // Queued under the lock: whoever sees Starting and sends something next queues it behind the start.
            val gen = generation
            submit { start(gen) }
        }
    }

    /** Stops the server politely and starts a new one, with a fresh count of restarts. */
    fun restart() {
        val old =
            synchronized(lock) {
                if (closed) return
                generation++
                crashes = 0
                cancelRetry()
                val old = detach()
                setState(ServerState.Starting)
                val gen = generation
                submit { start(gen) }
                old
            }
        if (old != null) NamedThreads.virtual("sluide-lsp-${spec.id}-restart") { shutdown(old) }
    }

    /** Runs [action] on the outbound thread with the running server, or `null` if none runs then. */
    fun onServer(action: (RunningServer?) -> Unit) {
        submit {
            val current = synchronized(lock) { running?.let { RunningServer(it, runningEpoch) } }
            action(current)
        }
    }

    /** Ends the server (`shutdown`, `exit`, kill after the timeouts) off the calling thread; once. */
    fun close() {
        val old =
            synchronized(lock) {
                if (closed) return
                closed = true
                generation++
                cancelRetry()
                setState(ServerState.Stopped)
                detach()
            }
        NamedThreads.virtual("sluide-lsp-${spec.id}-close") {
            outbound.shutdownNow()
            if (old != null) shutdown(old)
        }
    }

    private fun start(gen: Int) {
        try {
            startSteps(gen)
        } catch (_: InterruptedException) {
            // Closed while waiting (trust, start): close ends what was started.
        } catch (e: RuntimeException) {
            log.log(System.Logger.Level.ERROR, "lsp ${spec.id}: starting the server failed", e)
            val old =
                synchronized(lock) {
                    if (!isCurrentLocked(gen)) return
                    generation++
                    setState(ServerState.Failed("${spec.name} language server could not start: ${e.message ?: e}"))
                    detach()
                }
            old?.kill()
        }
    }

    /** One start; an unexpected exception (folder, trust, spec, starter) fails the server in [start]. */
    private fun startSteps(gen: Int) {
        if (!isCurrent(gen)) return
        val root = projectFolder()
        if (root == null) {
            settle(gen, ServerState.Stopped)
            return
        }
        val command = spec.command()
        if (command == null) {
            log.log(System.Logger.Level.INFO, "lsp ${spec.id}: no server available")
            settle(gen, ServerState.Unavailable)
            return
        }
        if (spec.runsProjectCode && !trusted(gen)) return
        val name = "${spec.id}#${processNumbers.incrementAndGet()}"
        val process =
            try {
                starter.start(name, command, root)
            } catch (e: IOException) {
                crashed(gen, "${spec.name} language server could not be started: ${e.message}")
                return
            }
        val features = ServerFeatures()
        val conn =
            LspConnection(
                process,
                newClient(root, features, name) {
                    isCurrent(gen)
                },
                features,
                "${spec.name} language server stopped",
            )
        synchronized(lock) {
            if (!isCurrentLocked(gen)) {
                conn.kill()
                return
            }
            connection = conn
        }
        NamedThreads.virtual("sluide-lsp-$name-exit") {
            val exit = process.waitFor()
            crashed(gen, "${spec.name} language server exited with code $exit")
        }
        conn.listen()
        val started = nanoTime()
        val result = initialize(gen, conn, root) ?: return
        features.initialized(result.capabilities)
        try {
            conn.server.initialized(InitializedParams())
        } catch (e: RuntimeException) {
            crashed(gen, "${spec.name} language server: ${e.message ?: e.toString()}")
            return
        }
        val epoch =
            synchronized(lock) {
                if (!isCurrentLocked(gen)) return
                running = conn
                runningEpoch = ++connects
                runningSince = nanoTime()
                setState(ServerState.Running)
                runningEpoch
            }
        val server = result.serverInfo?.let { " (${it.name} ${it.version.orEmpty()})" }.orEmpty()
        log.log(System.Logger.Level.INFO, "lsp $name initialized in ${(nanoTime() - started) / 1_000_000} ms$server")
        connected(epoch)
    }

    /** Waits for the trust checks; `false`, settled [ServerState.Untrusted] or failed, if the server may not run. */
    private fun trusted(gen: Int): Boolean {
        val checkId = spec.trustCheckId
        if (checkId == null) {
            settle(gen, ServerState.Failed("${spec.name} language server runs project code but names no trust check"))
            return false
        }
        if (trustGate.awaitAllows(checkId) { !isCurrent(gen) }) return true
        log.log(System.Logger.Level.INFO, "lsp ${spec.id}: not started, the project trust does not allow $checkId")
        settle(gen, ServerState.Untrusted)
        return false
    }

    private fun initialize(
        gen: Int,
        conn: LspConnection,
        root: Path,
    ): InitializeResult? =
        try {
            val answer = conn.server.initialize(initializeParams(root))
            val deadline = System.nanoTime() + timing.initialize.inWholeNanoseconds
            var result: InitializeResult? = null
            // Asks every 100 ms whether a restart or close replaced this start, so that the next start does not wait.
            while (result == null) {
                if (!isCurrent(gen)) {
                    answer.cancel(true)
                    return null
                }
                if (System.nanoTime() - deadline >= 0) {
                    crashed(gen, "${spec.name} language server did not answer initialize within ${timing.initialize}")
                    return null
                }
                result =
                    try {
                        answer.get(INITIALIZE_POLL_MILLIS, TimeUnit.MILLISECONDS)
                    } catch (_: TimeoutException) {
                        null
                    }
            }
            result
        } catch (e: ExecutionException) {
            crashed(gen, "${spec.name} language server refused initialize: ${(e.cause ?: e).message}")
            null
        } catch (_: InterruptedException) {
            // The session closed while waiting; close ends the process.
            null
        } catch (e: RuntimeException) {
            crashed(gen, "${spec.name} language server: ${e.message ?: e.toString()}")
            null
        }

    private fun initializeParams(root: Path) =
        InitializeParams().apply {
            processId = ProcessHandle.current().pid().toInt()
            clientInfo = ClientInfo("Thoreau")
            @Suppress("DEPRECATION")
            rootUri = LspUris.of(root)
            workspaceFolders = listOf(workspaceFolder(root))
            initializationOptions = spec.initializationOptions(root)
            capabilities = clientCapabilities().also(spec::adjustCapabilities)
            trace = "off"
        }

    /** The process of start [gen] ended or failed: restart after a pause, or fail after the last restart. */
    private fun crashed(
        gen: Int,
        message: String,
    ) {
        val old =
            synchronized(lock) {
                if (!isCurrentLocked(gen)) return
                generation++
                val ranFor = runningSince?.let { nanoTime() - it }
                if (ranFor != null && ranFor >= timing.stableAfter.inWholeNanoseconds) crashes = 0
                crashes++
                if (crashes > timing.backoff.size) {
                    log.log(System.Logger.Level.WARNING, "lsp ${spec.id}: $message; not restarted again")
                    setState(ServerState.Failed(message))
                } else {
                    val delay = timing.backoff[crashes - 1]
                    log.log(System.Logger.Level.WARNING, "lsp ${spec.id}: $message; restart $crashes in $delay")
                    setState(ServerState.Restarting(crashes))
                    val retryGen = generation
                    retry = scheduler.schedule(delay) { retryDue(retryGen) }
                }
                detach()
            }
        old?.kill()
    }

    private fun retryDue(gen: Int) {
        synchronized(lock) {
            if (!isCurrentLocked(gen)) return
            retry = null
            submit { start(gen) }
        }
    }

    private fun shutdown(old: LspConnection) =
        old.shutdown(timing.shutdown.inWholeMilliseconds, timing.exit.inWholeMilliseconds)

    private fun submit(task: () -> Unit) {
        try {
            outbound.execute(task)
        } catch (_: RejectedExecutionException) {
            // Closed: nothing is sent any more.
        }
    }

    /** Forgets the connection; the caller ends it outside the lock. Holds the lock. */
    private fun detach(): LspConnection? {
        val old = connection
        connection = null
        running = null
        runningSince = null
        return old
    }

    private fun cancelRetry() {
        retry?.close()
        retry = null
    }

    private fun settle(
        gen: Int,
        newState: ServerState,
    ) = synchronized(lock) { if (isCurrentLocked(gen)) setState(newState) }

    private fun setState(newState: ServerState) {
        state = newState
        publish(newState)
    }

    private fun isCurrent(gen: Int) = synchronized(lock) { isCurrentLocked(gen) }

    private fun isCurrentLocked(gen: Int) = gen == generation && !closed
}

/** The server that runs when something queued is sent, with the number of its connection. */
internal class RunningServer(
    private val connection: LspConnection,
    val epoch: Int,
) {
    val server: LanguageServer get() = connection.server

    /** Keeps a request sent to this server, so that it fails when the server stops. */
    fun track(future: CompletableFuture<*>) = connection.track(future)
}
