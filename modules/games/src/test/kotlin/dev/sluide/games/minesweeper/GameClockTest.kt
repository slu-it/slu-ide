package dev.sluide.games.minesweeper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameClockTest {
    private var now = 1_000L
    private val clock = GameClock { now }

    private fun advance(millis: Long) {
        now += millis
    }

    @Test
    fun `does not tick before start`() {
        advance(500)

        assertEquals(0, clock.elapsedMillis)
        assertFalse(clock.isStarted)
        assertFalse(clock.isTicking)
    }

    @Test
    fun `ticks after start`() {
        clock.start()
        advance(1500)

        assertTrue(clock.isTicking)
        assertEquals(1500, clock.elapsedMillis)
    }

    @Test
    fun `hidden and manual pause pause independently`() {
        clock.start()
        advance(100)
        clock.hidden = true
        advance(1000)
        clock.manuallyPaused = true
        advance(1000)
        clock.hidden = false
        advance(1000)
        assertFalse(clock.isTicking)
        assertEquals(100, clock.elapsedMillis)
        clock.manuallyPaused = false
        advance(50)

        assertTrue(clock.isTicking)
        assertEquals(150, clock.elapsedMillis)
    }

    @Test
    fun `stop freezes the time`() {
        clock.start()
        advance(300)
        clock.stop()
        advance(1000)
        clock.hidden = true
        clock.hidden = false

        assertTrue(clock.isStopped)
        assertFalse(clock.isTicking)
        assertEquals(300, clock.elapsedMillis)
    }

    @Test
    fun `reset goes back to zero and clears the manual pause but not hidden`() {
        clock.start()
        advance(300)
        clock.manuallyPaused = true
        clock.hidden = true

        clock.reset()
        advance(100)

        assertEquals(0, clock.elapsedMillis)
        assertFalse(clock.isStarted)
        assertFalse(clock.isStopped)
        assertFalse(clock.manuallyPaused)
        assertTrue(clock.hidden)
        clock.start()
        advance(100)
        assertEquals(0, clock.elapsedMillis)
        clock.hidden = false
        advance(100)
        assertEquals(100, clock.elapsedMillis)
    }

    @Test
    fun `starting twice keeps the time`() {
        clock.start()
        advance(200)
        clock.start()
        advance(200)

        assertEquals(400, clock.elapsedMillis)
    }
}
