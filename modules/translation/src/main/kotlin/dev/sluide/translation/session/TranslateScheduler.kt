package dev.sluide.translation.session

import dev.sluide.backgroundtasks.NamedThreads
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** Waits out the Translate tab's typing debounce; the task only hands the run to the worker. */
internal interface TranslateScheduler {
    /** Runs [task] on a background thread after [delay]; the returned function cancels it unless it has started. */
    fun schedule(
        delay: Duration,
        task: () -> Unit,
    ): () -> Unit

    /** Stops the timer; called when the model is disposed. */
    fun shutdown() = Unit

    companion object {
        /** A scheduler on one daemon thread of its own, ended by [shutdown]. */
        fun singleThread(): TranslateScheduler = ExecutorScheduler(NamedThreads.scheduled("sluide-translate-timer"))
    }
}

private class ExecutorScheduler(
    private val executor: ScheduledExecutorService,
) : TranslateScheduler {
    override fun schedule(
        delay: Duration,
        task: () -> Unit,
    ): () -> Unit {
        val future = executor.schedule(Runnable(task), delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        return { future.cancel(false) }
    }

    override fun shutdown() {
        executor.shutdownNow()
    }
}
