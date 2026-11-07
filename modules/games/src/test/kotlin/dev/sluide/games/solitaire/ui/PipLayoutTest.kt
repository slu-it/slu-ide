package dev.sluide.games.solitaire.ui

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PipLayoutTest {
    @ParameterizedTest
    @ValueSource(ints = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    fun `a number card has as many pips as its rank, all inside the area`(rank: Int) {
        val pips = PipLayout.pips(rank)
        assertEquals(rank, pips.size)
        assertTrue(pips.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 })
    }

    @ParameterizedTest
    @ValueSource(ints = [2, 3, 4, 5, 6, 8, 9, 10])
    fun `the arrangement is point symmetric with the pips of the lower half inverted`(rank: Int) {
        val pips = PipLayout.pips(rank)
        for (p in pips) {
            val mirror = pips.firstOrNull { near(it.x, 1 - p.x) && near(it.y, 1 - p.y) }
            assertNotNull(mirror, "no mirror of $p at rank $rank")
            if (!near(p.y, 0.5)) assertEquals(!p.inverted, mirror.inverted, "$p at rank $rank")
            assertEquals(p.y > 0.5, p.inverted, "$p at rank $rank")
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [2, 3, 4, 5, 6, 7, 8, 9, 10])
    fun `no two pips are close to each other`(rank: Int) {
        val pips = PipLayout.pips(rank)
        for (a in pips.indices) {
            for (b in a + 1 until pips.size) {
                val d = hypot(pips[a].x - pips[b].x, pips[a].y - pips[b].y)
                assertTrue(d >= 0.3, "pips ${pips[a]} and ${pips[b]} are $d apart at rank $rank")
            }
        }
    }

    @Test
    fun `the seven is the traditional asymmetric one with a single centre pip above the middle`() {
        val centre = PipLayout.pips(7).filter { near(it.x, 0.5) }
        assertEquals(1, centre.size)
        assertTrue(centre.single().y < 0.5)
    }

    @Test
    fun `the ace is one pip in the middle`() {
        val pip = PipLayout.pips(1).single()
        assertTrue(near(pip.x, 0.5) && near(pip.y, 0.5))
    }

    @ParameterizedTest
    @ValueSource(ints = [11, 12, 13])
    fun `the faces have no pips`(rank: Int) {
        assertTrue(PipLayout.pips(rank).isEmpty())
    }

    private fun near(
        a: Double,
        b: Double,
    ) = abs(a - b) < 1e-9
}
