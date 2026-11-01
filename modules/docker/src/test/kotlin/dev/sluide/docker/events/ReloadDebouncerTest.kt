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
}
