package dev.sluide.docker.tab

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.lang.ref.WeakReference
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class HandOffQueueTest {
    private val handOffs = mutableListOf<Runnable>()
    private val batches = mutableListOf<List<Int>>()
    private val queue = HandOffQueue<Int>({ handOffs += it }) { batches += it }

    @Test
    fun `a burst costs one hand-off and arrives as one batch in order`() {
        repeat(1000) { queue.add(it) }
        assertEquals(1, handOffs.size)
        handOffs.single().run()
        assertEquals(listOf((0 until 1000).toList()), batches)
    }

    @Test
    fun `an item after a drain asks for the next hand-off`() {
        queue.add(1)
        handOffs.removeLast().run()
        queue.add(2)
        assertEquals(1, handOffs.size)
        handOffs.removeLast().run()
        assertEquals(listOf(listOf(1), listOf(2)), batches)
    }

    @Test
    fun `nothing is consumed after cancel`() {
        queue.add(1)
        queue.cancel()
        queue.add(2)
        handOffs.forEach { it.run() }
        assertEquals(emptyList(), batches)
    }

    @Test
    fun `beyond its capacity the oldest items are dropped`() {
        val small = HandOffQueue<Int>({ handOffs += it }, capacity = 3) { batches += it }
        repeat(10) { small.add(it) }
        handOffs.single().run()
        assertEquals(listOf(listOf(7, 8, 9)), batches)
        small.add(10)
        handOffs.last().run()
        assertEquals(listOf(10), batches.last())
    }

    @Test
    fun `the capacity counts only what is still queued, not what was handed off`() {
        val small = HandOffQueue<Int>({ handOffs += it }, capacity = 3) { batches += it }
        small.add(1)
        small.add(2)
        handOffs.removeLast().run()
        (10..14).forEach(small::add)
        handOffs.removeLast().run()
        assertEquals(listOf(listOf(1, 2), listOf(12, 13, 14)), batches)
    }

    @Test
    fun `a hand-off that finds nothing queued consumes nothing`() {
        queue.add(1)
        val handOff = handOffs.single()
        handOff.run()
        handOff.run()
        assertEquals(listOf(listOf(1)), batches)
    }

    @Test
    fun `an item added after cancel asks for no hand-off`() {
        queue.add(1)
        handOffs.single().run()
        queue.cancel()
        queue.add(2)
        assertEquals(1, handOffs.size, "no new hand-off after cancel")
    }

    @Test
    fun `cancel lets go of the items still queued`() {
        val items = HandOffQueue<ByteArray>({ handOffs += it }) { }
        // The item lives only in the helper's frame; the queued hand-off holds the queue, so only cancel frees it.
        val reference = queued(items)
        items.cancel()
        assertTimeoutPreemptively(Duration.ofSeconds(10)) {
            while (reference.get() != null) {
                System.gc()
                Thread.yield()
            }
        }
    }

    private fun queued(items: HandOffQueue<ByteArray>): WeakReference<ByteArray> {
        val item = ByteArray(1024)
        items.add(item)
        return WeakReference(item)
    }
}
