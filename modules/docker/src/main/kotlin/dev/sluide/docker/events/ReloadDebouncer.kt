package dev.sluide.docker.events

import dev.sluide.docker.model.ResourceKind
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Collects the lists that events make stale and reloads them together: the first [add] after a reload starts a wait of
 * [delay], and when it is over [reload] gets every kind added meanwhile, on the [scheduler]'s thread. A burst of events
 * (`compose up` of ten services) thus costs one reload per list, and a steady stream still reloads every [delay]
 * instead of never. Thread-safe: events arrive on the stream's reader thread.
 */
internal class ReloadDebouncer(
    private val scheduler: Scheduler,
    private val delay: Duration = 200.milliseconds,
    private val reload: (Set<ResourceKind>) -> Unit,
) {
    private val lock = Any()
    private val pending = mutableSetOf<ResourceKind>()
    private var scheduled: AutoCloseable? = null

    /** Marks [kinds] stale; nothing happens for an empty set. */
    fun add(kinds: Set<ResourceKind>) {
        if (kinds.isEmpty()) return
        synchronized(lock) {
            pending += kinds
            if (scheduled == null) scheduled = scheduler.schedule(delay, ::fire)
        }
    }

    /** Drops what is pending; a later [add] starts again. */
    fun cancel() {
        synchronized(lock) {
            scheduled?.close()
            scheduled = null
            pending.clear()
        }
    }

    private fun fire() {
        val kinds =
            synchronized(lock) {
                scheduled = null
                pending.toSet().also { pending.clear() }
            }
        if (kinds.isNotEmpty()) reload(kinds)
    }
}
