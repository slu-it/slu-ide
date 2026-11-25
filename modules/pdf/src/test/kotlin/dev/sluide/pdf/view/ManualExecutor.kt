package dev.sluide.pdf.view

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * An executor whose tasks wait until [runAll] runs them on the calling thread, or run at once when [direct]. Like a
 * real one, it refuses tasks after [shutdown] but still runs those it holds.
 */
internal class ManualExecutor(
    private val direct: Boolean = false,
) : AbstractExecutorService() {
    private val tasks = ArrayDeque<Runnable>()
    private var shut = false
    var shutDownNow = false
        private set

    override fun execute(command: Runnable) {
        if (shut) throw RejectedExecutionException("shut down")
        if (direct) command.run() else tasks.addLast(command)
    }

    /** Runs the held tasks and those they add, in order. */
    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }

    override fun shutdown() {
        shut = true
    }

    override fun shutdownNow(): List<Runnable> {
        shut = true
        shutDownNow = true
        return tasks.toList().also { tasks.clear() }
    }

    override fun isShutdown() = shut

    override fun isTerminated() = shut && tasks.isEmpty()

    override fun awaitTermination(
        timeout: Long,
        unit: TimeUnit,
    ) = isTerminated
}

/** A stand-in for `Platform::runLater`: holds what is posted until [runAll]. */
internal class ManualFx : (Runnable) -> Unit {
    private val posted = ArrayDeque<Runnable>()

    override fun invoke(runnable: Runnable) {
        posted.addLast(runnable)
    }

    fun runAll() {
        while (posted.isNotEmpty()) posted.removeFirst().run()
    }
}
