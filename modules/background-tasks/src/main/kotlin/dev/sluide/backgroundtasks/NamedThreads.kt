package dev.sluide.backgroundtasks

import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private val log = System.getLogger(NamedThreads::class.java.name)

/** Threads whose tasks' failures are logged (ADR 0060): every throwable at ERROR with the thread's name and stack trace. */
object NamedThreads {
    /** Receives every failure; tests replace it to capture without a logging backend. */
    internal var report: (threadName: String, error: Throwable) -> Unit = { threadName, error ->
        log.log(System.Logger.Level.ERROR, "Task failed on thread $threadName", error)
    }

    /**
     * One daemon platform thread named [name]. A task given to `execute` that throws is logged and the thread lives on;
     * one given to `submit`/`invokeAll` is logged too, and its Future still completes exceptionally. Cancelled tasks
     * are not logged. Failures of `invokeAny` and of an `ExecutorCompletionService` are not logged.
     *
     * An idle executor ends its thread after a minute and is then garbage-collected unless referenced, so an abandoned
     * one shuts itself down; a new thread gets the same name. A plain `execute` command is queued wrapped, so the list
     * of `shutdownNow()`, `getQueue()` and `remove()` see wrappers.
     */
    fun single(name: String): ExecutorService = single(name, 60_000)

    internal fun single(
        name: String,
        keepAliveMillis: Long,
    ): ExecutorService =
        LoggingExecutor(factory(name)).apply {
            setKeepAliveTime(keepAliveMillis, TimeUnit.MILLISECONDS)
            allowCoreThreadTimeOut(true)
        }

    /**
     * Like [single], with scheduling; a periodic task that throws is logged (and stops, as the JDK does). Its thread
     * does not end when idle: callers must shut it down.
     */
    fun scheduled(name: String): ScheduledExecutorService = LoggingScheduledExecutor(factory(name))

    /** Starts [task] on a new virtual thread named [name]; a throwable is logged and not rethrown. */
    fun virtual(
        name: String,
        task: Runnable,
    ): Thread =
        Thread.ofVirtual().name(name).start {
            try {
                task.run()
            } catch (t: Throwable) {
                report(Thread.currentThread().name, t)
            }
        }

    private fun factory(name: String) = ThreadFactory { Thread(it, name).apply { isDaemon = true } }

    /** Logs the failure of a finished [task] the JDK's `afterExecute` way; plain `execute` failures are caught earlier. */
    private fun reportFailedFuture(
        task: Runnable?,
        thrown: Throwable?,
    ) {
        if (thrown != null) {
            report(Thread.currentThread().name, thrown)
        } else if (task is Future<*> && task.isDone) {
            try {
                task.get()
            } catch (e: ExecutionException) {
                report(Thread.currentThread().name, e.cause ?: e)
            } catch (_: CancellationException) {
                // Cancelled on purpose.
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private class LoggingExecutor(
        factory: ThreadFactory,
    ) : ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(), factory) {
        override fun execute(command: Runnable) {
            // submit() wraps in a FutureTask, which never throws; a raw command is guarded here so the thread survives.
            super.execute(
                if (command is Future<*>) {
                    command
                } else {
                    Runnable {
                        try {
                            command.run()
                        } catch (t: Throwable) {
                            report(Thread.currentThread().name, t)
                        }
                    }
                },
            )
        }

        override fun afterExecute(
            r: Runnable?,
            t: Throwable?,
        ) {
            super.afterExecute(r, t)
            reportFailedFuture(r, t)
        }
    }

    private class LoggingScheduledExecutor(
        factory: ThreadFactory,
    ) : ScheduledThreadPoolExecutor(1, factory) {
        override fun afterExecute(
            r: Runnable?,
            t: Throwable?,
        ) {
            super.afterExecute(r, t)
            reportFailedFuture(r, t)
        }
    }
}
