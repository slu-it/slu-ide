package dev.sluide.converting.model

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** Runs the Converting tool's detection and conversion off the FX thread, after a delay. */
internal interface ConvertingScheduler {
    /** Runs [task] on a background thread after [delay]; the returned function cancels it unless it has started. */
    fun schedule(
        delay: Duration,
        task: () -> Unit,
    ): () -> Unit

    /** Stops running tasks; called when the model is disposed. */
    fun shutdown() = Unit

    companion object {
        /** A scheduler on one daemon thread of its own. */
        fun singleThread(): ConvertingScheduler =
            ExecutorScheduler(
                Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "Converting").apply { isDaemon = true }
                },
            )
    }
}

private class ExecutorScheduler(
    private val executor: ScheduledExecutorService,
) : ConvertingScheduler {
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
