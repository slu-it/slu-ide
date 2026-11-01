package dev.sluide.docker.tab

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Carries items from a reader thread to the FX thread in batches: [add] (any thread) queues an item and, if no hand-off
 * is pending, asks [handOff] for one; that runs [consume] with everything queued by then. So a burst of a thousand
 * lines costs one hand-off, not a thousand. At most [capacity] items wait: beyond that the oldest are dropped. After
 * [cancel] nothing more is consumed.
 */
internal class HandOffQueue<T>(
    private val handOff: (Runnable) -> Unit,
    private val capacity: Int = Int.MAX_VALUE,
    private val consume: (List<T>) -> Unit,
) {
    private val queue = ConcurrentLinkedQueue<T>()
    private val waiting = AtomicInteger()
    private val scheduled = AtomicBoolean()

    @Volatile private var cancelled = false

    fun add(item: T) {
        if (cancelled) return
        queue.add(item)
        if (waiting.incrementAndGet() > capacity && queue.poll() != null) waiting.decrementAndGet()
        if (scheduled.compareAndSet(false, true)) handOff(::drain)
    }

    /** Drops what is queued and stops consuming. */
    fun cancel() {
        cancelled = true
        queue.clear()
        waiting.set(0)
    }

    private fun drain() {
        // Reset before polling: an item added after the poll ends schedules the next hand-off.
        scheduled.set(false)
        if (cancelled) return
        val items = generateSequence { queue.poll()?.also { waiting.decrementAndGet() } }.toList()
        if (items.isNotEmpty()) consume(items)
    }
}
