package dev.sluide.docker.tab

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
}
