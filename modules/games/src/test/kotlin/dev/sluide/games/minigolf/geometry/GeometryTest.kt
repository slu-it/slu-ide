package dev.sluide.games.minigolf.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

internal class GeometryTest {
    private fun assertNear(
        expected: Vec,
        actual: Vec,
    ) {
        assertEquals(expected.x, actual.x, 1e-9, "x of $actual")
        assertEquals(expected.y, actual.y, 1e-9, "y of $actual")
    }

    @Test
    fun `vectors add, subtract, scale and measure`() {
        val v = Vec(3.0, 4.0)
        assertEquals(Vec(4.0, 6.0), v + Vec(1.0, 2.0))
        assertEquals(Vec(2.0, 2.0), v - Vec(1.0, 2.0))
        assertEquals(Vec(6.0, 8.0), v * 2.0)
        assertEquals(11.0, v dot Vec(1.0, 2.0))
        assertEquals(5.0, v.length)
        assertNear(Vec(0.6, 0.8), v.normalized())
        assertTrue(Vec.ZERO.normalized().isZero)
    }

    @Test
    fun `a positive turn is clockwise on screen, y pointing down`() {
        val right = Vec(1.0, 0.0)
        assertNear(Vec(0.0, 1.0), right.rotated(Math.PI / 2))
        assertNear(Vec(0.0, 1.0), right.perpendicular())
        assertNear(Vec(0.0, -2.0), Vec.ofAngle(-Math.PI / 2, 2.0))
        assertEquals(Math.PI / 2, Vec(0.0, 5.0).angle(), 1e-12)
    }

    @ParameterizedTest
    @CsvSource(
        "5,7,5,0", // above the middle: straight down onto the segment
        "-4,3,0,0", // beyond the start: the end point
        "14,-1,10,0", // beyond the end: the other end point
    )
    fun `the closest point of a segment stays between its ends`(
        x: Double,
        y: Double,
        closestX: Double,
        closestY: Double,
    ) {
        val segment = Segment(Vec(0.0, 0.0), Vec(10.0, 0.0))
        assertEquals(Vec(closestX, closestY), segment.closestPoint(Vec(x, y)))
    }

    @Test
    fun `a polygon contains the points of its concave shape only`() {
        // An L: the notch at the top right is outside.
        val l =
            Polygon(
                listOf(Vec(0.0, 0.0), Vec(10.0, 0.0), Vec(10.0, 5.0), Vec(30.0, 5.0), Vec(30.0, 15.0), Vec(0.0, 15.0)),
            )
        assertTrue(l.contains(Vec(5.0, 2.0)))
        assertTrue(l.contains(Vec(25.0, 10.0)))
        assertFalse(l.contains(Vec(25.0, 2.0)))
        assertFalse(l.contains(Vec(-1.0, 10.0)))
    }

    @Test
    fun `the edges of a polygon close the outline`() {
        val a = Vec(0.0, 0.0)
        val b = Vec(4.0, 0.0)
        val c = Vec(0.0, 3.0)
        assertEquals(listOf(Segment(a, b), Segment(b, c), Segment(c, a)), Polygon(listOf(a, b, c)).edges())
    }

    @Test
    fun `a circle contains its border`() {
        val circle = Circle(Vec(10.0, 10.0), 5.0)
        assertTrue(circle.contains(Vec(15.0, 10.0)))
        assertFalse(circle.contains(Vec(15.1, 10.0)))
    }
}
