package dev.sluide.backgroundtasks

import javafx.beans.property.ReadOnlyStringProperty
import javafx.beans.property.ReadOnlyStringWrapper
import java.util.concurrent.CancellationException

/**
 * One piece of work running in the background, started with [BackgroundTasks.start] (ADR 0061). It ends exactly once,
 * with one log line: [close] (finished), [failed] (failed or cancelled) or, when the window closes first, abandoned;
 * later calls do nothing.
 */
class BackgroundTask internal constructor(
    private val tasks: BackgroundTasks,
    /** The number of its log lines, `task#<number>`. */
    internal val number: Int,
    name: String,
    private val onCancel: (() -> Unit)?,
    /** When it started, in the nanoseconds of the registry's clock; for the elapsed time. */
    val startedAt: Long,
) : AutoCloseable {
    private val nameWrapper = ReadOnlyStringWrapper(name)

    /** The name shown; updated on the FX thread. */
    val name: ReadOnlyStringProperty = nameWrapper.readOnlyProperty

    /** Whether the work can be cancelled, so its row shows ✕. */
    val cancellable: Boolean = onCancel != null

    /** The latest name, also before the FX thread has shown it; guarded by `this`. */
    private var currentName = name

    /**
     * Whether it has ended: finished, failed, cancelled or abandoned. Written under `this`, so no rename or cancel line
     * follows the end line.
     */
    @Volatile internal var ended = false
        private set

    /** Whether [cancel] was called; guarded by `this`. */
    private var cancelled = false

    /** Its timer to be shown while that has not run yet; FX thread. */
    internal var pending: Pending? = null

    /** Changes the name shown; any thread. Logged at INFO only when it changes; nothing once the task has ended. */
    fun rename(name: String) {
        synchronized(this) {
            if (ended || name == currentName) return
            tasks.report(System.Logger.Level.INFO, "task#$number: $currentName → $name", null)
            currentName = name
        }
        tasks.onFxThread { nameWrapper.set(nameNow()) }
    }

    /**
     * Asks the work to stop; FX thread. Calls the cancel function at most once, only if [cancellable] and running. The
     * task still ends when the work does; its failure then counts as cancelled (see [failed]).
     */
    fun cancel() {
        val cancel = onCancel ?: return
        synchronized(this) {
            if (ended || cancelled) return
            cancelled = true
            tasks.report(System.Logger.Level.INFO, "task#$number cancelled", null)
        }
        cancel()
    }

    /**
     * Ends the task with a failure the user is told about; any thread. Logged at WARNING, or at INFO as cancelled if
     * [cancel] was called before.
     */
    fun failed(message: String) =
        end { duration ->
            if (cancelled) {
                cancelledLine(duration)
            } else {
                EndLine(System.Logger.Level.WARNING, "failed after $duration: $currentName: $message")
            }
        }

    /**
     * Ends the task with an unexpected exception; any thread. Logged at ERROR with the stack trace; at INFO as cancelled
     * if [error] is a [CancellationException] or an [InterruptedException], or [cancel] was called before.
     */
    fun failed(error: Throwable) =
        end { duration ->
            if (error is CancellationException || error is InterruptedException || cancelled) {
                cancelledLine(duration)
            } else {
                EndLine(System.Logger.Level.ERROR, "failed after $duration: $currentName", error)
            }
        }

    /** Ends the task; any thread, idempotent. Logged at INFO. */
    override fun close() = end { EndLine(System.Logger.Level.INFO, "finished in $it: $currentName") }

    /**
     * Ends the task like [close], with [detail] about the outcome in its end line, e.g. a response's status:
     * `task#7 finished in 1.23 s: GET example.com (200, 12 bytes)`. Any thread; nothing once the task has ended.
     */
    fun close(detail: String) = end { EndLine(System.Logger.Level.INFO, "finished in $it: $currentName ($detail)") }

    /** The window closed while it was running. */
    internal fun abandon() = end { EndLine(System.Logger.Level.INFO, "abandoned after $it: $currentName") }

    private fun cancelledLine(duration: String) =
        EndLine(System.Logger.Level.INFO, "cancelled after $duration: $currentName")

    private fun nameNow() = synchronized(this) { currentName }

    private class EndLine(
        val level: System.Logger.Level,
        val text: String,
        val error: Throwable? = null,
    )

    /**
     * Logs the one end line unless the task has ended already, then it leaves the list. The [line] is chosen under the
     * lock, so a [cancel] cannot slip in between deciding cancelled or failed and logging it.
     */
    private fun end(line: (duration: String) -> EndLine) {
        synchronized(this) {
            if (ended) return
            ended = true
            val end = line(seconds(tasks.elapsed(this)))
            tasks.report(end.level, "task#$number ${end.text}", end.error)
        }
        tasks.ended(this)
    }
}
