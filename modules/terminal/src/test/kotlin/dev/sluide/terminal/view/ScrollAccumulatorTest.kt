package dev.sluide.terminal.view

import kotlin.test.Test
import kotlin.test.assertEquals

class ScrollAccumulatorTest {
    private val accumulator = ScrollAccumulator()

    private fun scroll(
        events: Int,
        percent: Int,
        up: Boolean = false,
    ) = List(events) { accumulator.add(up, percent) }

    @Test
    fun `at 100 percent every event scrolls three lines, up towards the history`() {
        assertEquals(listOf(3, 3, 3), scroll(3, 100))
        assertEquals(listOf(-3, -3), scroll(2, 100, up = true))
    }

    @Test
    fun `at 50 percent two events scroll three lines`() {
        assertEquals(listOf(1, 2, 1, 2), scroll(4, 50))
    }

    @Test
    fun `at 10 percent ten events scroll exactly three lines`() {
        assertEquals(3, scroll(10, 10).sum())
        assertEquals(30, scroll(100, 10).sum())
    }

    @Test
    fun `at 200 percent every event scrolls six lines`() {
        assertEquals(listOf(-6, -6), scroll(2, 200, up = true))
    }

    @Test
    fun `turning around drops the fraction left over`() {
        assertEquals(listOf(1), scroll(1, 50))
        assertEquals(listOf(-1), scroll(1, 50, up = true), "not -2 with the half line of the other direction")
        assertEquals(listOf(-2), scroll(1, 50, up = true))
    }

    @Test
    fun `reset drops the fraction left over`() {
        assertEquals(listOf(1), scroll(1, 50))

        accumulator.reset()

        assertEquals(listOf(1), scroll(1, 50))
    }

    @Test
    fun `counting notches, every event is one at 100 percent`() {
        val notches = ScrollAccumulator(perEvent = 1)

        assertEquals(listOf(1, 1), List(2) { notches.add(false, 100) })
        assertEquals(listOf(0, 1, 0, 1), List(4) { notches.add(false, 50) })
        assertEquals(listOf(-2), List(1) { notches.add(true, 200) })
    }
}
