package dev.sluide.games.doubles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GesturesTest {
    @Test
    fun `the dominant axis decides, positive dy is down`() {
        assertEquals(Direction.RIGHT, dragDirection(50.0, 10.0, 20.0))
        assertEquals(Direction.LEFT, dragDirection(-50.0, -10.0, 20.0))
        assertEquals(Direction.DOWN, dragDirection(5.0, 30.0, 20.0))
        assertEquals(Direction.UP, dragDirection(-5.0, -30.0, 20.0))
    }

    @Test
    fun `a drag shorter than the threshold is nothing`() {
        assertNull(dragDirection(19.0, 3.0, 20.0))
        assertEquals(Direction.RIGHT, dragDirection(20.0, 3.0, 20.0))
        assertNull(dragDirection(0.0, 0.0, 20.0))
    }

    @Test
    fun `equal axes are ambiguous`() {
        assertNull(dragDirection(40.0, 40.0, 20.0))
        assertNull(dragDirection(-40.0, 40.0, 20.0))
    }
}
