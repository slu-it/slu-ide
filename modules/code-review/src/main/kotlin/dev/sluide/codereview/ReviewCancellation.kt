package dev.sluide.codereview

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Cancels one load of a review; thread-safe. Each action registered with [onCancel] runs once. */
class ReviewCancellation {
    private val cancelled = AtomicBoolean()
    private val actions = ConcurrentLinkedQueue<() -> Unit>()

    val isCancelled: Boolean
        get() = cancelled.get()

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) runActions()
    }

    /** Runs [action] when this is cancelled, or at once if it already is. */
    fun onCancel(action: () -> Unit) {
        actions += action
        if (isCancelled) runActions()
    }

    /** Runs the registered actions, each taken off the queue first so that it runs once; the first failure is thrown. */
    private fun runActions() {
        var failure: Throwable? = null
        while (true) {
            val action = actions.poll() ?: break
            try {
                action()
            } catch (e: Throwable) {
                failure?.addSuppressed(e) ?: run { failure = e }
            }
        }
        failure?.let { throw it }
    }
}
