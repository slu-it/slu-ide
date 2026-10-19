package dev.sluide.backgroundtasks

import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val log = System.getLogger(BackgroundTasks::class.java.name)

/** How long a task runs before it is shown, so short work neither flickers nor fills the list (ADR 0061). */
private val SHOW_DELAY: Duration = Duration.ofMillis(500)

/** Numbers the tasks of all windows, so a task's log lines can be told apart (ADR 0060). */
private val numbers = AtomicInteger()

/** Something scheduled with `later` that has not run yet; [cancel] keeps it from running. */
internal fun interface Pending {
    fun cancel()
}

/**
 * The work running in the background in one window (ADR 0061): every task's start and end is logged at INFO with its
 * duration (ADR 0060), and a task that runs at least [delay] is [shown] until it ends.
 *
 * [dispatch] runs code on the JavaFX thread, [clock] gives nanoseconds, and [later] runs code on the JavaFX thread after
 * a delay.
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class BackgroundTasks internal constructor(
    private val dispatch: (Runnable) -> Unit,
    private val clock: () -> Long,
    private val later: (Duration, Runnable) -> Pending,
    private val delay: Duration,
) : AutoCloseable {
    @Autowired
    constructor() : this(Platform::runLater, System::nanoTime, ::pause, SHOW_DELAY)

    /** Receives every log line; tests replace it to capture without a logging backend. */
    internal var report: (level: System.Logger.Level, message: String, error: Throwable?) -> Unit =
        { level, message, error ->
            if (error == null) log.log(level, message) else log.log(level, message, error)
        }

    private val shownTasks = FXCollections.observableArrayList<BackgroundTask>()

    /** Shown tasks (running at least the delay), oldest first. FX thread only. */
    val shown: ObservableList<BackgroundTask> = FXCollections.unmodifiableObservableList(shownTasks)

    /** The tasks that have not ended yet; abandoned when the window closes. */
    private val running = ConcurrentHashMap.newKeySet<BackgroundTask>()

    @Volatile private var closed = false

    /**
     * Starts a task named [name]; any thread. Logs `task#<n> started: <name>` at INFO. A [cancel] (called on the FX
     * thread, must not block) makes its row show ✕. The caller must end it: [BackgroundTask.close] or
     * [BackgroundTask.failed].
     */
    fun start(
        name: String,
        cancel: (() -> Unit)? = null,
    ): BackgroundTask {
        val task = BackgroundTask(this, numbers.incrementAndGet(), name, cancel, clock())
        report(System.Logger.Level.INFO, "task#${task.number} started: $name", null)
        running += task
        if (!closed) dispatch(Runnable { scheduleShow(task) })
        return task
    }

    /**
     * Runs [work] on the calling thread as a task: started, then closed. A throwable is reported with
     * [BackgroundTask.failed] and rethrown: a `CancellationException`, an `InterruptedException` or any throwable after
     * the task was cancelled ends it as cancelled (INFO), others as failed (ERROR).
     */
    fun <T> track(
        name: String,
        cancel: (() -> Unit)? = null,
        work: () -> T,
    ): T {
        val task = start(name, cancel)
        val result =
            try {
                work()
            } catch (t: Throwable) {
                task.failed(t)
                throw t
            }
        task.close()
        return result
    }

    /** The window closes: tasks still running are logged as abandoned; later starts still log but are never shown. */
    override fun close() {
        closed = true
        for (task in running.sortedBy { it.number }) task.abandon()
        dispatch(Runnable { shownTasks.clear() })
    }

    /** How long [task] has been running. */
    internal fun elapsed(task: BackgroundTask): Duration = Duration.ofNanos(clock() - task.startedAt)

    /** FX thread: shows [task] once it has run [delay], unless it ends before. */
    private fun scheduleShow(task: BackgroundTask) {
        if (task.ended || closed) return
        val remaining = delay - elapsed(task)
        if (remaining.isNegative || remaining.isZero) {
            show(task)
        } else {
            task.pending = later(remaining, Runnable { show(task) })
        }
    }

    private fun show(task: BackgroundTask) {
        task.pending = null
        if (task.ended || closed) return
        val index = shownTasks.indexOfFirst { it.number > task.number }.let { if (it < 0) shownTasks.size else it }
        shownTasks.add(index, task)
    }

    /** Any thread: [task] has ended (logged by it); it is no longer running and leaves the list. */
    internal fun ended(task: BackgroundTask) {
        running -= task
        dispatch(
            Runnable {
                task.pending?.cancel()
                task.pending = null
                shownTasks.remove(task)
            },
        )
    }

    internal fun onFxThread(action: () -> Unit) = dispatch(Runnable(action))

    companion object {
        /**
         * A registry for the tests of other modules, without the JavaFX toolkit: its tasks are logged as usual and
         * [shown] at once, until they end, so that a test sees what runs and can cancel it ([BackgroundTask.cancel]).
         * What would run on the JavaFX thread runs on the calling thread, one at a time. [record] also receives every
         * log line of its tasks (`task#7 started: Fetching origin…`, `task#7 failed after 0.001 s: …`), so that tests
         * can assert what was registered, renamed and how it ended.
         */
        fun forTests(record: (String) -> Unit = {}): BackgroundTasks {
            val lock = Any()
            return BackgroundTasks(
                { action -> synchronized(lock) { action.run() } },
                System::nanoTime,
                // Never asked: without a delay, a task is shown when it starts.
                { _, _ -> Pending {} },
                Duration.ZERO,
            ).apply {
                val logged = report
                report = { level, message, error ->
                    logged(level, message, error)
                    record(message)
                }
            }
        }
    }
}

/** Runs [action] on the JavaFX thread after [delay]; must be called on it. */
private fun pause(
    delay: Duration,
    action: Runnable,
): Pending {
    val pause = PauseTransition(javafx.util.Duration.millis(delay.toMillis().toDouble()))
    pause.setOnFinished { action.run() }
    pause.play()
    return Pending { pause.stop() }
}

/** A duration with three significant digits, e.g. `0.123 s`, `1.23 s`, `12.3 s` (ADR 0060). */
internal fun seconds(duration: Duration): String {
    val seconds = duration.toNanos() / 1e9
    val format =
        when {
            seconds < 1 -> "%.3f s"
            seconds < 10 -> "%.2f s"
            seconds < 100 -> "%.1f s"
            else -> "%.0f s"
        }
    return String.format(Locale.ROOT, format, seconds)
}
