package dev.sluide.gitlog.operations

import dev.sluide.backgroundtasks.BackgroundTask
import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.git.GitCancellation
import dev.sluide.git.GitResult
import dev.sluide.gitlog.data.GitLogWrites
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs the [RefOperation]s of a git log one at a time (ADR 0041): on [background] (the thread `sluide-git-write`),
 * the outcome handled through [dispatch]. While one runs, [running] is `true` and no other starts; while it is one
 * that can be [cancelled][cancel] (a fetch, ADR 0043) and is not yet, [cancellable] is `true`. The outcome of a run
 * started before the last [forget] is dropped. Used on the JavaFX thread (or whatever [dispatch] stands for in tests).
 *
 * Each run is a task of [tasks] (ADR 0061), started on [background], named by what the header says it does and renamed
 * with its progress, with ✕ if it can be cancelled. It ends as finished, failed (git's message) or cancelled.
 *
 * Each run tells [writeStarted] before git runs, with whether it [holds the status][RefOperation.holdsStatus], and
 * [writeFinished] through [dispatch] once it ended, whatever happened (also when its outcome is dropped or it threw),
 * before its outcome is handled (ADR 0062).
 */
internal class RefWriter(
    private val writes: GitLogWrites,
    private val background: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
    private val tasks: BackgroundTasks,
    private val writeStarted: (holdsStatus: Boolean) -> Unit = {},
    private val writeFinished: () -> Unit = {},
) {
    private val _running = ReadOnlyBooleanWrapper(false)
    val running: ReadOnlyBooleanProperty = _running.readOnlyProperty

    private val _cancellable = ReadOnlyBooleanWrapper(false)
    val cancellable: ReadOnlyBooleanProperty = _cancellable.readOnlyProperty

    private val generation = AtomicLong()

    /** The cancellation of the cancellable run going on; `null` without one. */
    private var cancellation: GitCancellation? = null

    /** The task of the run going on, once its thread has started it and until it ends there. */
    @Volatile private var task: BackgroundTask? = null

    /**
     * Runs [operation] in the repository at [root] and hands its outcome to [done], unless one runs already: then
     * returns `false`. A failure of the operation itself (not git's) is an outcome with its message.
     */
    fun run(
        root: Path,
        operation: RefOperation,
        done: (RefOutcome) -> Unit,
    ): Boolean = run(root, operation, {}, done)

    /** [run], and what the operation says it does meanwhile goes to [progress], unless its outcome is dropped. */
    fun run(
        root: Path,
        operation: RefOperation,
        progress: (String) -> Unit,
        done: (RefOutcome) -> Unit,
    ): Boolean {
        if (_running.get()) return false
        _running.set(true)
        val current = generation.get()
        val token = GitCancellation()
        if (operation.cancellable) {
            cancellation = token
            _cancellable.set(true)
        }
        writeStarted(operation.holdsStatus)
        try {
            background {
                var outcome: RefOutcome? = null
                try {
                    outcome = write(root, operation, current, token, progress)
                } finally {
                    // Also after a throw: the status must not wait for this write forever.
                    val ended = outcome
                    dispatch {
                        _running.set(false)
                        if (cancellation === token) {
                            cancellation = null
                            _cancellable.set(false)
                        }
                        writeFinished()
                        if (ended != null && generation.get() == current) done(ended)
                    }
                }
            }
        } catch (e: RuntimeException) {
            // E.g. the thread was shut down with the window.
            _running.set(false)
            cancellation = null
            _cancellable.set(false)
            writeFinished()
            throw e
        }
        return true
    }

    /** Runs [operation] as a task on the calling thread (`background`); a failure of its own is an outcome. */
    private fun write(
        root: Path,
        operation: RefOperation,
        current: Long,
        token: GitCancellation,
        progress: (String) -> Unit,
    ): RefOutcome {
        // Its ✕ cancels this run only; on the FX thread, as cancel().
        val cancel = if (operation.cancellable) ({ if (cancellation === token) cancel() }) else null
        val task = tasks.start(operation.progress, cancel).also { this@RefWriter.task = it }
        val context =
            RunContext(token) { text ->
                task.rename(text)
                dispatch { if (generation.get() == current) progress(text) }
            }
        val outcome =
            try {
                operation.run(writes, root, context)
            } catch (e: RuntimeException) {
                task.failed(e)
                RefOutcome(GitResult(false, e.message ?: e.toString()), null)
            } catch (t: Throwable) {
                task.failed(t)
                throw t
            } finally {
                this@RefWriter.task = null
            }
        // By what the operation says: a Cancel after it was done does not make it cancelled.
        when {
            outcome.cancelled -> task.failed(CancellationException("Cancelled"))
            !outcome.result.success -> task.failed(outcome.result.message.ifBlank { operation.failure })
            else -> task.close()
        }
        return outcome
    }

    /**
     * Cancels the run going on if it is [cancellable] (git is stopped, the operation ends with what it did by then);
     * whether there was one. Never blocks: git is stopped on the thread it runs on.
     */
    fun cancel(): Boolean {
        val running = cancellation ?: return false
        // Logs the cancel; the task's ✕ calls back here, which then only stops git.
        task?.cancel()
        running.cancel()
        _cancellable.set(false)
        return true
    }

    /** Drops the outcome of the run going on, e.g. for another repository. */
    fun forget() {
        generation.incrementAndGet()
    }
}
