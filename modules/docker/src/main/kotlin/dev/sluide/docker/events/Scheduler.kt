package dev.sluide.docker.events

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** Runs tasks after a delay; tests replace it with a clock they advance by hand. */
internal fun interface Scheduler {
    /** Runs [task] once after [delay] on the scheduler's thread; closing the result cancels it if it has not run. */
    fun schedule(
        delay: Duration,
        task: Runnable,
    ): AutoCloseable
}

/** A [Scheduler] on [executor]; whoever created the executor shuts it down. */
internal class ExecutorScheduler(
    private val executor: ScheduledExecutorService,
) : Scheduler {
    override fun schedule(
        delay: Duration,
        task: Runnable,
    ): AutoCloseable {
        val future = executor.schedule(task, delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        return AutoCloseable { future.cancel(false) }
    }
}
