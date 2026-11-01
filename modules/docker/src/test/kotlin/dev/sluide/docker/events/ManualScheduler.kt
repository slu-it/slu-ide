package dev.sluide.docker.events

import kotlin.time.Duration

/** A [Scheduler] whose clock moves only by [advance], which runs the due tasks on the calling thread. */
internal class ManualScheduler : Scheduler {
    private class Task(
        val due: Duration,
        val task: Runnable,
    ) {
        var cancelled = false
    }

    private val tasks = mutableListOf<Task>()

    /** The time since the start. */
    var now: Duration = Duration.ZERO
        private set

    /** The delays of the tasks that are due later, from now. */
    val pending: List<Duration> get() = synchronized(tasks) { tasks.filter { !it.cancelled }.map { it.due - now } }

    override fun schedule(
        delay: Duration,
        task: Runnable,
    ): AutoCloseable {
        val scheduled = Task(now + delay, task)
        synchronized(tasks) { tasks += scheduled }
        return AutoCloseable { scheduled.cancelled = true }
    }

    /** Moves the clock by [time], running each task that becomes due, in order. */
    fun advance(time: Duration) {
        val end = now + time
        while (true) {
            val next =
                synchronized(tasks) {
                    tasks.filter { !it.cancelled && it.due <= end }.minByOrNull { it.due }?.also { tasks -= it }
                } ?: break
            now = next.due
            next.task.run()
        }
        now = end
        synchronized(tasks) { tasks.removeAll { it.cancelled } }
    }
}
