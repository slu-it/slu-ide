package dev.sluide.commandruns

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.terminal.Terminals
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * What the command runners of one IDE window share (ADR 0088): the window's [terminals] the runs are embedded from,
 * its [backgroundTasks] that list them, and the thread that ends stopped runs after their grace.
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class CommandRunSession(
    val terminals: Terminals,
    val backgroundTasks: BackgroundTasks,
) : AutoCloseable {
    /** Fires the delayed kills of stopped runs. */
    private val stopTimer = NamedThreads.scheduled("Command stop")

    /** Stop escalations schedule their kill here. */
    internal val stopScheduler =
        StopScheduler { delay, action ->
            val future = stopTimer.schedule(action, delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            return@StopScheduler { future.cancel(false) }
        }

    override fun close() {
        stopTimer.shutdownNow()
    }
}
