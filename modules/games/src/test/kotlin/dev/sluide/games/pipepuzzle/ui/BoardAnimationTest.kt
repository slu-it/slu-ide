package dev.sluide.games.pipepuzzle.ui

import dev.sluide.games.pipepuzzle.Pos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoardAnimationTest {
    private val a = Pos(1, 1)
    private val b = Pos(2, 2)

    @Test
    fun `a clockwise turn goes from -90 to 0 over 100 ms`() {
        val animation = BoardAnimation()
        animation.startTurn(a, true, 1000)

        assertEquals(-90.0, animation.turnAngleAt(1000))
        assertEquals(-45.0, animation.turnAngleAt(1050))
        assertEquals(a, animation.turningAt(1099))
        assertNull(animation.turningAt(1100))
        assertEquals(0.0, animation.turnAngleAt(1100))
    }

    @Test
    fun `a counter-clockwise turn goes from +90 to 0`() {
        val animation = BoardAnimation()
        animation.startTurn(a, false, 0)

        assertEquals(90.0, animation.turnAngleAt(0))
        assertEquals(45.0, animation.turnAngleAt(50))
    }

    @Test
    fun `a new turn finishes the running one at once`() {
        val animation = BoardAnimation()
        animation.startTurn(a, true, 0)
        animation.startTurn(b, false, 30)

        assertEquals(b, animation.turningAt(40))
        assertEquals(81.0, animation.turnAngleAt(40), 1e-9)
    }

    @Test
    fun `the flow shows one more layer every 40 ms and then ends`() {
        val animation = BoardAnimation()
        animation.startFlow(4, 500)

        assertEquals(1, animation.layersAt(500))
        assertEquals(1, animation.layersAt(539))
        assertEquals(2, animation.layersAt(540))
        assertEquals(3, animation.layersAt(619))
        assertTrue(animation.isRunning(619))
        assertNull(animation.layersAt(620))
        assertFalse(animation.isRunning(10_000))
    }

    @Test
    fun `nothing runs before anything starts and after finish`() {
        val animation = BoardAnimation()
        assertFalse(animation.isRunning(0))

        animation.startTurn(a, true, 0)
        animation.startFlow(10, 0)
        assertTrue(animation.isRunning(10))
        animation.finish()

        assertFalse(animation.isRunning(10))
        assertNull(animation.turningAt(10))
        assertNull(animation.layersAt(10))
    }

    @Test
    fun `finishing the flow leaves a running turn`() {
        val animation = BoardAnimation()
        animation.startTurn(a, true, 0)
        animation.startFlow(10, 0)

        animation.finishFlow()

        assertNull(animation.layersAt(10))
        assertEquals(a, animation.turningAt(10))
    }
}
