package dev.sluide.games.solitaire.ui

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BurstPhysicsTest {
    private val still = Particle(100.0, 100.0, 0.0, 0.0)

    @Test
    fun `gravity accelerates a card downwards`() {
        val first = BurstPhysics.step(still, 0.1, 1000.0)
        val second = BurstPhysics.step(first, 0.1, 1000.0)

        assertTrue(first.vy > 0.0)
        assertTrue(second.vy > first.vy)
        assertTrue(second.y - first.y > first.y - still.y)
    }

    @Test
    fun `sideways speed stays and moves the card`() {
        val p = BurstPhysics.step(still.copy(vx = 200.0), 0.5, 1000.0)

        assertEquals(200.0, p.vx)
        assertEquals(100.0, p.x - still.x)
    }

    @Test
    fun `a card reaching the floor bounces up with damped speed`() {
        val falling = Particle(0.0, 495.0, 0.0, 600.0)

        val p = BurstPhysics.step(falling, 0.1, 500.0)

        assertEquals(500.0, p.y)
        assertTrue(p.vy < 0.0)
        assertEquals((600.0 + BurstPhysics.GRAVITY * 0.1) * BurstPhysics.DAMPING, -p.vy, 1e-9)
    }

    @Test
    fun `the card is opaque early, fades in the last stretch and is gone at the end`() {
        val early = BurstPhysics.step(still, 0.1, 1e9)
        val fading = BurstPhysics.step(still.copy(age = BurstPhysics.LIFETIME - BurstPhysics.FADE / 2), 0.0, 1e9)
        val end = BurstPhysics.step(still.copy(age = BurstPhysics.LIFETIME - 0.01), 0.02, 1e9)

        assertEquals(1.0, early.alpha)
        assertEquals(0.5, fading.alpha, 1e-9)
        assertEquals(0.0, end.alpha)
        assertTrue(BurstPhysics.isGone(end))
        assertFalse(BurstPhysics.isGone(early))
    }

    @Test
    fun `a launch starts at the origin, kicks upwards and stays within the speed bounds`() {
        val random = Random(1)
        repeat(200) {
            val p = BurstPhysics.launch(30.0 to 40.0, random)

            assertEquals(30.0, p.x)
            assertEquals(40.0, p.y)
            assertTrue(p.vx in -BurstPhysics.MAX_VX..BurstPhysics.MAX_VX)
            assertTrue(-p.vy in BurstPhysics.MIN_KICK..BurstPhysics.MAX_KICK)
            assertEquals(0.0, p.age)
            assertEquals(1.0, p.alpha)
        }
    }

    @Test
    fun `a launch is deterministic for a seed and differs from card to card`() {
        val random = Random(7)
        val a = List(5) { BurstPhysics.launch(0.0 to 0.0, random) }
        val again = Random(7)
        val b = List(5) { BurstPhysics.launch(0.0 to 0.0, again) }

        assertEquals(a, b)
        assertNotEquals(a[0], a[1])
    }
}
