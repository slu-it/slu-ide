package dev.sluide.games.orbitguard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

internal class ShieldTest {
    private fun shield() = Shield(0, 0)

    /** Pixels around (12, 8) by squared distance. */
    private fun ring(
        minSquared: Int,
        maxSquared: Int,
    ) = (-3..3).flatMap { dy -> (-3..3).map { dx -> dx to dy } }.filter { (dx, dy) ->
        dx * dx + dy * dy in
            minSquared..maxSquared
    }

    private fun Shield.solidIn(pixels: List<Pair<Int, Int>>) = pixels.count { (dx, dy) -> isSolid(12 + dx, 8 + dy) }

    @Test
    fun `the shield is a chamfered bastion`() {
        val shield = shield()
        assertEquals(24, shield.width)
        assertEquals(16, shield.height)
        listOf(0 to 0, 23 to 0, 0 to 15, 23 to 15, 3 to 0, 0 to 3, 0 to 12, 20 to 0).forEach { (x, y) ->
            assertFalse(shield.isSolid(x, y), "($x, $y) should be cut")
        }
        listOf(4 to 0, 1 to 3, 0 to 4, 12 to 8, 1 to 12, 4 to 15, 11 to 15, 12 to 15, 12 to 14).forEach { (x, y) ->
            assertTrue(shield.isSolid(x, y), "($x, $y) should be solid")
        }
        assertEquals(344, shield.solidCount())
        assertFalse(shield.isSolid(-1, 5))
        assertFalse(shield.isSolid(5, 16))
    }

    @Test
    fun `each shield owns its bitmap`() {
        val a = shield()
        val b = shield()
        a.clearChunk(12, 8, ScriptedRandom(0.0))
        assertEquals(344, b.solidCount())
    }

    @Test
    fun `a hit always clears the pixels within distance 2`() {
        val shield = shield()
        shield.clearChunk(12, 8, ScriptedRandom(double = 0.99))
        assertEquals(0, shield.solidIn(ring(0, 4)))
        assertEquals(ring(5, 9).size, shield.solidIn(ring(5, 9)))
    }

    @Test
    fun `pixels at distance 3 are cleared when the random says so`() {
        val shield = shield()
        shield.clearChunk(12, 8, ScriptedRandom(double = 0.0))
        assertEquals(0, shield.solidIn(ring(0, 9)))
        assertEquals(ring(10, 18).size, shield.solidIn(ring(10, 18)))
    }

    @Test
    fun `pixels at distance 3 are cleared about half the time with a seeded random`() {
        val shield = shield()
        shield.clearChunk(12, 8, Random(3))
        val left = shield.solidIn(ring(5, 9))
        assertTrue(left in 1 until ring(5, 9).size, "left: $left")
    }

    @Test
    fun `the first solid pixel is found from above and from below`() {
        val shield = shield()
        assertEquals(0, shield.firstSolid(12, -5, 20))
        assertEquals(15, shield.firstSolid(12, 20, -5))
        assertEquals(4, shield.firstSolid(0, -5, 20))
        assertEquals(11, shield.firstSolid(0, 20, -5))
        assertNull(shield.firstSolid(30, -5, 20))
        assertNull(shield.firstSolid(12, -5, -1))
    }

    @Test
    fun `the search sees a hole made by an earlier hit`() {
        val shield = shield()
        shield.clearChunk(12, 1, ScriptedRandom(0.0))
        assertEquals(5, shield.firstSolid(12, 0, 15))
    }

    @Test
    fun `search and clearing use field coordinates`() {
        val shield = Shield(100, 50)
        assertEquals(50, shield.firstSolid(112, 40, 70))
        shield.clearChunk(112, 58, ScriptedRandom(0.99))
        assertFalse(shield.isSolid(12, 8))
    }

    @Test
    fun `an overlapping enemy clears exactly the pixels under its set sprite pixels`() {
        val sprite = Sprites.enemy(EnemyKind.BRUTE, 0)
        val shield = shield()
        shield.clearOverlap(sprite, 6, 4)
        val set = sprite.rows.sumOf { row -> row.count { it == '#' } }
        assertEquals(344 - set, shield.solidCount())
        assertTrue(shield.isSolid(6 + 6, 4 + 3), "an empty sprite pixel keeps the shield")
        assertFalse(shield.isSolid(6, 4 + 3))
    }

    @Test
    fun `an enemy partly outside the shield clears only what overlaps`() {
        val shield = shield()
        shield.clearOverlap(Sprites.enemy(EnemyKind.BRUTE, 0), -6, 12)
        // rows 12..15 of the sprite's left half lie outside; 1 + 1 + 3 + 4 set pixels land on solid ones
        assertEquals(335, shield.solidCount())
    }
}
