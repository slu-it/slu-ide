package dev.sluide.docker.events

import dev.sluide.docker.model.ResourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class ReloadDebouncerTest {
    private val scheduler = ManualScheduler()
    private val reloads = mutableListOf<Set<ResourceKind>>()
    private val debouncer = ReloadDebouncer(scheduler, 200.milliseconds) { reloads += it }

    @Test
    fun `a burst of events reloads every list it touched once, after the delay`() {
        debouncer.add(setOf(ResourceKind.CONTAINER))
        scheduler.advance(100.milliseconds)
        debouncer.add(setOf(ResourceKind.CONTAINER, ResourceKind.VOLUME))
        debouncer.add(setOf(ResourceKind.NETWORK))
        scheduler.advance(99.milliseconds)
        assertEquals(emptyList(), reloads, "not before the delay is over")

        scheduler.advance(1.milliseconds)

        assertEquals(listOf(setOf(ResourceKind.CONTAINER, ResourceKind.VOLUME, ResourceKind.NETWORK)), reloads)
    }

    @Test
    fun `a steady stream still reloads every delay and nothing is lost between the waits`() {
        repeat(5) {
            debouncer.add(setOf(ResourceKind.CONTAINER))
            scheduler.advance(100.milliseconds)
        }
        debouncer.add(setOf(ResourceKind.IMAGE))
        scheduler.advance(1000.milliseconds)

        assertEquals(
            listOf(
                setOf(ResourceKind.CONTAINER),
                setOf(ResourceKind.CONTAINER),
                setOf(ResourceKind.CONTAINER, ResourceKind.IMAGE),
            ),
            reloads,
        )
    }

    @Test
    fun `empty sets schedule nothing and a cancel drops what is pending`() {
        debouncer.add(emptySet())
        assertEquals(emptyList(), scheduler.pending)

        debouncer.add(setOf(ResourceKind.IMAGE))
        debouncer.cancel()
        scheduler.advance(1000.milliseconds)
        assertEquals(emptyList(), reloads)

        debouncer.add(setOf(ResourceKind.VOLUME))
        scheduler.advance(200.milliseconds)
        assertEquals(listOf(setOf(ResourceKind.VOLUME)), reloads, "it starts again after a cancel")
    }

    @Test
    fun `a cancel closes the wait, so that it cannot fire for what is added after it`() {
        debouncer.add(setOf(ResourceKind.IMAGE))
        scheduler.advance(100.milliseconds)
        debouncer.cancel()
        assertEquals(emptyList(), scheduler.pending, "the wait is closed")

        debouncer.add(setOf(ResourceKind.VOLUME))
        scheduler.advance(100.milliseconds)
        assertEquals(emptyList(), reloads, "the first wait would be over now")
        scheduler.advance(100.milliseconds)

        assertEquals(listOf(setOf(ResourceKind.VOLUME)), reloads)
    }

    @Test
    fun `a reload does not repeat the kinds of the one before`() {
        debouncer.add(setOf(ResourceKind.CONTAINER))
        scheduler.advance(200.milliseconds)
        debouncer.add(setOf(ResourceKind.IMAGE))
        scheduler.advance(200.milliseconds)

        assertEquals(listOf(setOf(ResourceKind.CONTAINER), setOf(ResourceKind.IMAGE)), reloads)
    }

    @Test
    fun `a wait that ran after the cancel reloads nothing`() {
        // A task that already started when cancel closed its wait still runs: it finds nothing pending.
        var task: Runnable? = null
        val running =
            ReloadDebouncer({ _, run ->
                task = run
                AutoCloseable { }
            }, 200.milliseconds) { reloads += it }

        running.add(setOf(ResourceKind.IMAGE))
        running.cancel()
        task?.run()

        assertEquals(emptyList(), reloads)
    }
}
