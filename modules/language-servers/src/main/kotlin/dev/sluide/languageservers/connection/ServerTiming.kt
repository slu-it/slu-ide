package dev.sluide.languageservers.connection

import dev.sluide.backgroundtasks.NamedThreads
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The waits of a server's life; tests shorten them. */
internal data class ServerTiming(
    /** The pause before restart 1, 2, 3 after a crash; a crash after the last one fails the server. */
    val backoff: List<Duration> = listOf(1.seconds, 4.seconds, 15.seconds),
    /** A server that ran this long before it crashed starts the count of restarts again. */
    val stableAfter: Duration = 3.minutes,
    /** How long the answer to `initialize` may take before the start counts as a crash. */
    val initialize: Duration = 30.seconds,
    /** How long the answer to `shutdown` may take at close. */
    val shutdown: Duration = 2.seconds,
    /** How long the server may take to exit after `exit` before it is killed. */
    val exit: Duration = 1.seconds,
    /** How long the changes of a workspace folder settle before it is refreshed or its files' folders are asked again. */
    val workspaceDebounce: Duration = 1.seconds,
    /** How long a `didOpen` waits for the spec to name its document's workspace folder before the window folder serves it. */
    val workspaceFolderWait: Duration = 10.seconds,
)

/** Runs tasks after a delay; tests replace it with one they run by hand. */
internal fun interface Scheduler {
    /** Runs [task] once after [delay]; closing the result cancels it if it has not run. */
    fun schedule(
        delay: Duration,
        task: Runnable,
    ): AutoCloseable
}

/** A [Scheduler] that sleeps on a virtual thread per task: retries are rare, so nothing needs shutting down. */
internal object SleepingScheduler : Scheduler {
    override fun schedule(
        delay: Duration,
        task: Runnable,
    ): AutoCloseable {
        val thread =
            NamedThreads.virtual("sluide-lsp-retry") {
                try {
                    Thread.sleep(delay.inWholeMilliseconds.coerceAtLeast(0))
                } catch (_: InterruptedException) {
                    return@virtual
                }
                task.run()
            }
        return AutoCloseable { thread.interrupt() }
    }
}
