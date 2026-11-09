package dev.sluide.games.minigolf.ui

import dev.sluide.games.minigolf.geometry.Vec
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HoleInOneBurstTest {
    @Test
    fun `the burst is active for 90 steps after the start and then ends`() {
        val burst = HoleInOneBurst(Random(1))
        assertFalse(burst.isActive(0))

        burst.start(Vec(100.0, 100.0), step = 500)
        assertTrue(burst.isActive(500))
        assertTrue(burst.isActive(589))
        assertFalse(burst.isActive(590))
    }

    @Test
    fun `the sparks start at the cup, fade and are gone after the duration`() {
        val burst = HoleInOneBurst(Random(1))
        burst.start(Vec(100.0, 100.0), 0)

        val first = burst.sparksAt(0.0)
        assertTrue(first.isNotEmpty())
        assertTrue(first.all { it.position == Vec(100.0, 100.0) && it.alpha == 1.0 })
        assertTrue(burst.sparksAt(1.4).all { it.alpha < 1.0 })
        assertEquals(emptyList(), burst.sparksAt(HoleInOneBurst.DURATION))
    }

    @Test
    fun `the sparks fly away from the cup`() {
        val burst = HoleInOneBurst(Random(1))
        burst.start(Vec(100.0, 100.0), 0)

        assertTrue(burst.sparksAt(0.3).any { it.position.distanceTo(Vec(100.0, 100.0)) > 10.0 })
    }
}
