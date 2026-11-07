package dev.sluide.games.orbitguard

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

internal class FleetTest {
    private fun brute(x: Double) = FleetEnemy(EnemyKind.BRUTE, 0, 0, x, 40.0)

    @ParameterizedTest
    @CsvSource("55,1.0,50", "1,1.0,1", "1,0.8,1", "1,1.25,1", "55,1.25,40", "28,1.0,26")
    fun `the interval shrinks with the enemies left and scales with the speed`(
        alive: Int,
        speed: Double,
        expected: Int,
    ) {
        assertEquals(expected, Fleet.intervalFor(alive, speed))
    }

    @Test
    fun `a slower fleet marches less often`() {
        assertTrue(Fleet.intervalFor(55, 0.8) > Fleet.intervalFor(55, 1.0))
    }

    @Test
    fun `a new fleet is laid out in a grid`() {
        val fleet = Fleet.create(1, 1.0)
        assertEquals(55, fleet.alive)
        val first = fleet.enemies.first()
        assertEquals(EnemyKind.SCOUT, first.kind)
        assertEquals(65.0, first.x)
        assertEquals(32.0, first.y)
        val raider = fleet.enemies.first { it.row == 1 && it.column == 1 }
        assertEquals(81.0, raider.x)
        assertEquals(46.0, raider.y)
        assertEquals(EnemyKind.BRUTE, fleet.enemies.last().kind)
    }

    @Test
    fun `the fleet marches every interval steps, first to the right`() {
        val fleet = Fleet.create(1, 1.0)
        val x = fleet.enemies.first().x
        repeat(49) { assertFalse(fleet.tick()) }
        assertEquals(x, fleet.enemies.first().x)
        assertTrue(fleet.tick())
        assertEquals(x + 2, fleet.enemies.first().x)
    }

    @Test
    fun `a march that would leave the field drops 8 px and reverses`() {
        val fleet = Fleet(listOf(brute(301.0)), 1.0)
        assertTrue(fleet.tick())
        assertEquals(48.0, fleet.enemies.single().y)
        assertEquals(301.0, fleet.enemies.single().x)
        assertEquals(-1, fleet.direction)
        fleet.tick()
        assertEquals(299.0, fleet.enemies.single().x)
    }

    @Test
    fun `a march that ends exactly at the limit is allowed`() {
        val fleet = Fleet(listOf(brute(300.0)), 1.0)
        fleet.tick()
        assertEquals(302.0, fleet.enemies.single().x)
        assertEquals(40.0, fleet.enemies.single().y)
        assertEquals(1, fleet.direction)
    }

    @Test
    fun `the left edge also drops and reverses`() {
        val fleet = Fleet(listOf(brute(301.0)), 1.0)
        repeat(149) { fleet.tick() }
        assertEquals(5.0, fleet.enemies.single().x)
        assertEquals(48.0, fleet.enemies.single().y)
        fleet.tick()
        assertEquals(5.0, fleet.enemies.single().x)
        assertEquals(56.0, fleet.enemies.single().y)
        assertEquals(1, fleet.direction)
    }

    @Test
    fun `the animation frame toggles with each march step only`() {
        val fleet = Fleet(listOf(brute(100.0), brute(120.0)), 1.0)
        assertEquals(0, fleet.frame)
        assertFalse(fleet.tick())
        assertEquals(0, fleet.frame)
        assertTrue(fleet.tick())
        assertEquals(1, fleet.frame)
        fleet.tick()
        fleet.tick()
        assertEquals(0, fleet.frame)
    }

    @Test
    fun `the frame also toggles on a drop`() {
        val fleet = Fleet(listOf(brute(301.0)), 1.0)
        fleet.tick()
        assertEquals(1, fleet.frame)
    }

    @Test
    fun `the lowest alive enemy of a column`() {
        val fleet = Fleet.create(1, 1.0)
        val lowest = fleet.lowestInColumn(3)!!
        assertEquals(4, lowest.row)
        fleet.remove(lowest)
        assertEquals(3, fleet.lowestInColumn(3)!!.row)
        fleet.enemies.filter { it.column == 3 }.forEach { fleet.remove(it) }
        assertEquals(null, fleet.lowestInColumn(3))
        assertFalse(3 in fleet.columns())
    }

    @Test
    fun `the fleet speeds up as enemies fall`() {
        val fleet = Fleet.create(1, 1.0)

        fun stepsToNextMarch(): Int {
            var steps = 0
            do steps++ while (!fleet.tick())
            return steps
        }
        assertEquals(50, stepsToNextMarch())
        fleet.enemies.drop(28).forEach { fleet.remove(it) }
        assertEquals(28, fleet.alive)
        assertEquals(26, stepsToNextMarch())
        assertEquals(26, stepsToNextMarch())
        fleet.enemies.drop(1).forEach { fleet.remove(it) }
        assertEquals(1, stepsToNextMarch())
        assertEquals(1, stepsToNextMarch())
    }
}
