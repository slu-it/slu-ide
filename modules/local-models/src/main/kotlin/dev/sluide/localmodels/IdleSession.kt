package dev.sluide.localmodels

import dev.sluide.backgroundtasks.NamedThreads
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.time.Duration

private val log = System.getLogger(IdleSession::class.java.name)

/**
 * One loaded model (an ONNX session, say) shared app-wide: [use] calls are serialized, the value is loaded by the
 * first one (on its thread) and closed [idle] after the last one, on a scheduled thread named [name]. [close] closes
 * it at once and refuses later use.
 *
 * Blocking. Loading and running a model call native code, which pins virtual threads: callers use platform threads.
 */
class IdleSession<T : AutoCloseable> internal constructor(
    private val name: String,
    private val idle: Duration,
    private val load: () -> T,
    private val scheduler: ScheduledExecutorService,
) : AutoCloseable {
    constructor(name: String, idle: Duration, load: () -> T) : this(name, idle, load, idleScheduler(name))

    private val lock = ReentrantLock()

    @Volatile private var value: T? = null

    private var idleTask: ScheduledFuture<*>? = null

    @Volatile private var closed = false

    /**
     * Runs [block] with the value, loading it first if needed; waits while another call runs.
     *
     * @throws InterruptedException when the thread is interrupted while it waits.
     * @throws IllegalStateException after [close].
     */
    @Throws(InterruptedException::class)
    fun <R> use(block: (T) -> R): R {
        lock.lockInterruptibly()
        try {
            check(!closed) { "$name is closed" }
            val current = value ?: load().also { value = it }
            return block(current)
        } finally {
            try {
                // close() sets the flag before it waits for the lock; when it did so during this use, the value is
                // closed here, before close() gets the lock (and then finds nothing left to close).
                if (closed) unloadQuietly() else scheduleUnload()
            } finally {
                lock.unlock()
            }
        }
    }

    /** Whether the value is loaded now. */
    fun loaded(): Boolean = value != null

    /** Closes the value at once (after a running [use]); later uses fail. */
    override fun close() {
        closed = true
        scheduler.shutdownNow()
        lock.lock()
        try {
            unloadLocked()
        } finally {
            lock.unlock()
        }
    }

    private fun unloadLocked() {
        val old = value
        value = null
        old?.close()
    }

    private fun scheduleUnload() {
        idleTask?.cancel(false)
        idleTask =
            try {
                scheduler.schedule(::unloadIfIdle, idle.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            } catch (_: RejectedExecutionException) {
                null
            }
    }

    private fun unloadIfIdle() {
        // A running use holds the lock; it schedules the unload again when it ends.
        if (!lock.tryLock()) return
        try {
            unloadQuietly()
        } finally {
            lock.unlock()
        }
    }

    /** Closes the value; a failure is logged, the value is dropped anyway. */
    private fun unloadQuietly() {
        try {
            unloadLocked()
        } catch (e: Exception) {
            log.log(System.Logger.Level.WARNING, "Cannot close the value of $name", e)
        }
    }

    private companion object {
        fun idleScheduler(name: String): ScheduledExecutorService =
            (NamedThreads.scheduled(name) as ScheduledThreadPoolExecutor).also { it.removeOnCancelPolicy = true }
    }
}
