package dev.sluide.docker

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.docker.cli.DockerCli
import dev.sluide.docker.cli.DockerConnection
import dev.sluide.docker.cli.DockerException
import dev.sluide.docker.cli.ResolvedDocker
import dev.sluide.docker.cli.connectDocker
import dev.sluide.docker.events.ExecutorScheduler
import dev.sluide.docker.events.ReloadDebouncer
import dev.sluide.docker.events.Scheduler
import dev.sluide.docker.events.reloadsFor
import dev.sluide.docker.model.Container
import dev.sluide.docker.model.Image
import dev.sluide.docker.model.Network
import dev.sluide.docker.model.ResourceKind
import dev.sluide.docker.model.Volume
import dev.sluide.docker.settings.DockerSettings
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val log = System.getLogger(DockerSession::class.java.name)

/**
 * The container engine of one IDE window (ADR 0117): the CLI found for the executable of the Docker settings, the
 * engine's [state], and the lists of [containers], [images], [volumes] and [networks], kept current by one shared
 * `docker events` stream while anything [subscribe]s (the Docker tab, later the Compose views).
 *
 * Threading: the executable is looked up (once, through the login shell) and the lists are loaded on the session's
 * worker thread, one thing after the other; nothing here blocks the calling thread. [state] and the lists change on the
 * FX thread (the [fx] hand-off). [submit] and [openStream] run their work on threads of their own.
 *
 * Life cycle: the first subscription looks the executable up (again if the setting changed or it was not found
 * before), loads all four lists and starts the events stream; events reload the lists they concern after a short
 * [debounce]. When the stream ends or a load fails, the state turns [EngineState.Unavailable] and the session retries
 * after [firstRetry], doubling up to [maxRetry], as long as anything subscribes; once the engine answers again,
 * everything is reloaded. Closing the last subscription stops the stream and the retries; state and lists keep their
 * last values. [close] (the window closes) stops everything.
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class DockerSession internal constructor(
    /** The executable setting: `docker`, `podman` or a path; a change takes effect at once while subscribed. */
    private val executable: ObservableValue<String>,
    /** Looks the executable up and connects; blocking, called on the worker. */
    private val connect: (executable: String) -> DockerConnection,
    /** Runs the session's own work, one task after the other. */
    private val worker: Executor,
    /** The timer of the debounce and of the retries. */
    private val scheduler: Scheduler,
    /** Hands state and list changes to the FX thread. */
    private val fx: (Runnable) -> Unit = Platform::runLater,
    private val debounce: Duration = 200.milliseconds,
    private val firstRetry: Duration = 2.seconds,
    private val maxRetry: Duration = 30.seconds,
    /** Ends the threads behind [worker] and [scheduler] when the session closes. */
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    @Autowired
    constructor(settings: DockerSettings) :
        this(settings, NamedThreads.single("Docker"), NamedThreads.scheduled("Docker timers"))

    private constructor(settings: DockerSettings, worker: ExecutorService, timers: ScheduledExecutorService) : this(
        executable = settings.executable,
        connect = { value -> connectDocker(value, System.getenv()) },
        worker = worker,
        scheduler = ExecutorScheduler(timers),
        onClose = {
            worker.shutdownNow()
            timers.shutdownNow()
        },
    )

    private val stateWrapper = ReadOnlyObjectWrapper<EngineState>(EngineState.Idle)
    private val containerList = FXCollections.observableArrayList<Container>()
    private val imageList = FXCollections.observableArrayList<Image>()
    private val volumeList = FXCollections.observableArrayList<Volume>()
    private val networkList = FXCollections.observableArrayList<Network>()

    /** Whether the engine is reached; changes on the FX thread. */
    val state: ReadOnlyObjectProperty<EngineState> = stateWrapper.readOnlyProperty

    /** All containers of the engine, as `ps -a` lists them; replaced as a whole on the FX thread. */
    val containers: ObservableList<Container> = FXCollections.unmodifiableObservableList(containerList)

    /** All images, one per tag; replaced as a whole on the FX thread. */
    val images: ObservableList<Image> = FXCollections.unmodifiableObservableList(imageList)

    /** All volumes; replaced as a whole on the FX thread. */
    val volumes: ObservableList<Volume> = FXCollections.unmodifiableObservableList(volumeList)

    /** All networks; replaced as a whole on the FX thread. */
    val networks: ObservableList<Network> = FXCollections.unmodifiableObservableList(networkList)

    private val subscribers = AtomicInteger()
    private val setting = AtomicReference(executable.value ?: "")
    private val streams = ConcurrentHashMap.newKeySet<AutoCloseable>()
    private val settingListener = ChangeListener<String> { _, _, value -> settingChanged(value ?: "") }
    private val debouncer = ReloadDebouncer(scheduler, debounce) { kinds -> work { reload(kinds) } }

    @Volatile private var closed = false

    // Only touched on the worker, apart from close().
    @Volatile private var connection: DockerConnection.Ready? = null
    private var connectedFor: String? = null

    @Volatile private var events: AutoCloseable? = null
    private var eventsGeneration = 0

    @Volatile private var retry: AutoCloseable? = null
    private var retryAttempt = 0
    private var current: EngineState = EngineState.Idle

    init {
        executable.addListener(settingListener)
    }

    /**
     * The executable and environment the CLI runs with, once looked up; for commands of the IDE's own, such as a shell
     * in a container (`docker exec -it` in a terminal tab).
     */
    val docker: ResolvedDocker? get() = connection?.docker

    /**
     * Keeps the session watching the engine until the result is closed (once; later calls do nothing). The first
     * subscription connects, loads and starts the events stream; see the class description.
     */
    fun subscribe(): AutoCloseable {
        check(!closed) { "The Docker session is closed" }
        if (subscribers.incrementAndGet() == 1) work { connectAndWatch(userAsked = false) }
        val done = AtomicBoolean()
        return AutoCloseable {
            if (done.compareAndSet(false, true) && subscribers.decrementAndGet() == 0) work { stopWatching() }
        }
    }

    /**
     * Reloads everything now (F5, Retry): looks the executable up again if it was not found or the setting changed,
     * and restarts the events stream if subscribed and it is not running.
     */
    fun refresh() {
        work {
            cancelRetry()
            connectAndWatch(userAsked = true)
        }
    }

    /**
     * Runs [action] with the CLI on a thread of its own, named after [name] (`docker-<name>`), for the actions of the
     * views (start, remove, pull, …). The future completes there, with the result or the failure: a [DockerException]
     * from the CLI, or one saying that Docker is not available when the CLI has not been found (yet). The CLI logs its
     * commands and failures; the caller shows them. The lists follow through the events stream.
     *
     * Cancelling: `cancel(true)` on the returned future (not on a stage derived from it) completes it as cancelled at
     * once and interrupts the action's thread if the action runs; an action not started yet does not start. A blocking
     * [DockerCli] call then ends: [DockerCli.pull] and the other calls kill their CLI and throw, which is dropped, as
     * the future is already cancelled. `cancel(false)` only completes the future as cancelled and lets the action run
     * to its end. Once the action has ended, cancelling does nothing.
     */
    fun <T> submit(
        name: String,
        action: (DockerCli) -> T,
    ): CompletableFuture<T> {
        val future = ActionFuture<T>()
        val cli = connection?.cli
        if (cli == null || closed) {
            future.completeExceptionally(DockerException("Docker is not available"))
            return future
        }
        NamedThreads.virtual("docker-$name") {
            if (!future.started(Thread.currentThread())) return@virtual
            try {
                future.complete(action(cli))
            } catch (e: DockerException) {
                future.completeExceptionally(e)
            } catch (t: Throwable) {
                if (!future.isCancelled) log.log(System.Logger.Level.ERROR, "Docker action $name failed", t)
                future.completeExceptionally(t)
            } finally {
                future.ended()
            }
        }
        return future
    }

    /**
     * Opens a stream with the CLI (`logs`, `stats`) on a thread of its own and returns its handle at once: closing it
     * closes the stream, also one still being opened. If it cannot be opened (or Docker is not available), [onFailure]
     * gets the failure on the FX thread (the [fx] hand-off), always after this returned, also when the handle was
     * closed meanwhile. The stream's own callbacks run on its reader thread (see [DockerCli]). Streams still open when
     * the session closes are closed with it.
     */
    fun openStream(
        name: String,
        open: (DockerCli) -> AutoCloseable,
        onFailure: (Throwable) -> Unit,
    ): AutoCloseable {
        val handle = LateStream()
        streams += handle
        submit(name, open).whenComplete { stream, failure ->
            if (stream != null) handle.opened(stream) else fx { onFailure(failure.unwrapped()) }
        }
        return handle
    }

    override fun close() {
        if (closed) return
        closed = true
        executable.removeListener(settingListener)
        debouncer.cancel()
        retry?.close()
        events?.close()
        streams.toList().forEach { it.close() }
        onClose()
    }

    private fun settingChanged(value: String) {
        setting.set(value)
        if (subscribers.get() > 0) refresh()
    }

    private fun connectAndWatch(userAsked: Boolean) {
        val firstTime = current is EngineState.Idle || current is EngineState.NotFound
        if (firstTime || (userAsked && current !is EngineState.Available)) publish(EngineState.Checking)
        val ready = connected() ?: return
        try {
            val engine = ready.cli.engine()
            // The stream first, so that nothing that happens during the loads is missed: its events reload once the
            // loads are done (they queue behind them on the worker), and a reload too many costs one listing.
            if (subscribers.get() > 0 && events == null) startEvents(ready.cli)
            ResourceKind.entries.forEach { load(it, ready.cli) }
            retryAttempt = 0
            publish(EngineState.Available(engine))
        } catch (e: RuntimeException) {
            outage(e)
        }
    }

    /** The connection for the current setting, looked up if there is none yet; `null` with [EngineState.NotFound]. */
    private fun connected(): DockerConnection.Ready? {
        val value = setting.get()
        connection?.let { if (connectedFor == value) return it }
        stopEvents()
        connection = null
        val result =
            try {
                connect(value)
            } catch (e: RuntimeException) {
                log.log(System.Logger.Level.ERROR, "Looking up $value failed", e)
                DockerConnection.NotFound(e.message ?: e.toString())
            }
        if (closed) return null
        return when (result) {
            is DockerConnection.Ready -> {
                connection = result
                connectedFor = value
                result
            }

            is DockerConnection.NotFound -> {
                connectedFor = null
                publish(EngineState.NotFound(result.message))
                null
            }
        }
    }

    private fun load(
        kind: ResourceKind,
        cli: DockerCli,
    ) {
        when (kind) {
            ResourceKind.CONTAINER -> show(containerList, cli.containers())
            ResourceKind.IMAGE -> show(imageList, cli.images())
            ResourceKind.VOLUME -> show(volumeList, cli.volumes())
            ResourceKind.NETWORK -> show(networkList, cli.networks())
        }
    }

    private fun reload(kinds: Set<ResourceKind>) {
        val ready = connection ?: return
        if (current !is EngineState.Available) return
        try {
            ResourceKind.entries.filter { it in kinds }.forEach { load(it, ready.cli) }
        } catch (e: RuntimeException) {
            outage(e)
        }
    }

    private fun startEvents(cli: DockerCli) {
        val generation = ++eventsGeneration
        val stream =
            cli.events(
                onEvent = { event -> debouncer.add(reloadsFor(event)) },
                onEnd = { failure -> work { eventsEnded(generation, failure) } },
            )
        events = stream
        if (closed) stream.close()
    }

    private fun eventsEnded(
        generation: Int,
        failure: Throwable?,
    ) {
        if (generation != eventsGeneration || events == null) return
        events = null
        outage(failure ?: DockerException("The events stream of the engine ended"))
    }

    /** The engine stopped answering: [EngineState.Unavailable] with the CLI's message, retried while subscribed. */
    private fun outage(failure: Throwable) {
        log.log(System.Logger.Level.INFO, "Docker unavailable: ${failure.message}")
        stopEvents()
        publish(EngineState.Unavailable(failure.message ?: failure.toString()))
        if (subscribers.get() == 0 || closed) return
        // One retry at a time: a subscription that ended and a new one during the outage must not start a second chain.
        cancelRetry()
        val delay = retryDelay(retryAttempt++, firstRetry, maxRetry)
        lateinit var handle: AutoCloseable
        handle =
            scheduler.schedule(delay) {
                work {
                    // A retry cancelled after it was due, or replaced by a newer one, does nothing.
                    if (retry !== handle) return@work
                    retry = null
                    if (subscribers.get() > 0) connectAndWatch(userAsked = false)
                }
            }
        retry = handle
    }

    private fun stopWatching() {
        if (subscribers.get() > 0) return
        stopEvents()
        cancelRetry()
    }

    private fun stopEvents() {
        debouncer.cancel()
        eventsGeneration++
        events?.close()
        events = null
    }

    private fun cancelRetry() {
        retry?.close()
        retry = null
    }

    private fun publish(state: EngineState) {
        current = state
        fx { stateWrapper.set(state) }
    }

    private fun <T> show(
        list: ObservableList<T>,
        items: List<T>,
    ) {
        fx { if (list != items) list.setAll(items) }
    }

    private fun work(task: () -> Unit) {
        if (closed) return
        try {
            worker.execute { if (!closed) task() }
        } catch (_: RejectedExecutionException) {
            // Closed meanwhile.
        }
    }

    /** A stream handle that may be closed before the stream it stands for is open. */
    private inner class LateStream : AutoCloseable {
        private var stream: AutoCloseable? = null
        private var closedEarly = false

        fun opened(opened: AutoCloseable) {
            val closeNow = synchronized(this) { closedEarly.also { if (!it) stream = opened } }
            if (closeNow) opened.close()
        }

        override fun close() {
            streams -= this
            val open =
                synchronized(this) {
                    stream.also {
                        closedEarly = true
                        stream = null
                    }
                }
            open?.close()
        }
    }
}

/** The future of [DockerSession.submit]: `cancel(true)` interrupts the action's thread while the action runs. */
private class ActionFuture<T> : CompletableFuture<T>() {
    private var thread: Thread? = null

    /** The action starts on [thread]; `false` if the future was cancelled before, then it must not start. */
    @Synchronized
    fun started(thread: Thread): Boolean {
        if (isCancelled) return false
        this.thread = thread
        return true
    }

    /** The action ended; a later cancel must not interrupt its thread. */
    @Synchronized
    fun ended() {
        thread = null
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        val cancelled = super.cancel(mayInterruptIfRunning)
        if (cancelled && mayInterruptIfRunning) {
            synchronized(this) { thread?.interrupt() }
        }
        return cancelled
    }
}

/** The wait before retry number [attempt] (from 0): [first], doubled each time, at most [max]. */
internal fun retryDelay(
    attempt: Int,
    first: Duration,
    max: Duration,
): Duration {
    var delay = first
    repeat(attempt.coerceAtMost(30)) { delay = (delay * 2).coerceAtMost(max) }
    return delay.coerceAtMost(max)
}

private fun Throwable.unwrapped(): Throwable =
    if (this is java.util.concurrent.CompletionException && cause != null) cause!! else this
