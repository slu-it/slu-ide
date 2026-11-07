package dev.sluide.games.blockbreaker

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

internal class BallTest {
    private val paddle = PaddleBox(100.0, 40.0)
    private val empty = BrickGrid()
    private val hits = mutableListOf<BrickHitResult>()

    private fun grid(vararg rows: String) = BrickGrid().also { it.load(rows.toList()) }

    private fun Ball.move(
        distance: Double,
        bricks: BrickGrid = empty,
    ) = advance(distance, paddle, bricks, hits)

    private fun Ball.degreesFromHorizontal() = Math.toDegrees(asin(abs(dy)))

    @ParameterizedTest
    @CsvSource(
        "4.5,100,-1,0.3,X,4,1",
        "311.5,100,1,0.3,X,312,-1",
        "100,16.5,0.3,-1,Y,16,1",
    )
    fun `walls and the top wall reflect the ball and push it out`(
        x: Double,
        y: Double,
        dirX: Double,
        dirY: Double,
        axis: String,
        boundary: Double,
        signAfter: Double,
    ) {
        val ball = Ball(x, y, dirX, dirY)
        ball.move(1.0)
        if (axis == "X") {
            assertEquals(boundary, ball.x, 1e-9)
            assertTrue(ball.dx * signAfter > 0, "x direction flipped")
            assertEquals(Math.signum(dirY), Math.signum(ball.dy))
        } else {
            assertEquals(boundary, ball.y, 1e-9)
            assertTrue(ball.dy * signAfter > 0, "y direction flipped")
            assertEquals(Math.signum(dirX), Math.signum(ball.dx))
        }
    }

    @ParameterizedTest
    @CsvSource("118,0", "139,0.9545454545", "97,-0.9545454545")
    fun `the paddle sends the ball up by 60 degrees times the offset`(
        x: Double,
        offset: Double,
    ) {
        val ball = Ball(x, 216.0, 0.0, 1.0)
        val bounces = ball.move(1.0)
        assertEquals(1, bounces)
        val angle = Math.toRadians(offset * 60)
        assertEquals(sin(angle), ball.dx, 1e-6)
        assertEquals(-cos(angle), ball.dy, 1e-6)
        assertEquals(216.0, ball.y, 1e-9)
    }

    @Test
    fun `a ball moving up through the paddle does not bounce`() {
        val ball = Ball(118.0, 218.0, 0.0, -1.0)
        assertEquals(0, ball.move(1.0))
        assertEquals(-1.0, ball.dy, 1e-9)
        assertEquals(217.0, ball.y, 1e-9)
    }

    @Test
    fun `a brick hit from below reflects vertically and pushes the ball out`() {
        val bricks = grid("1111111111111")
        val ball = Ball(100.0, 40.5, 0.2, -1.0)
        ball.move(1.0, bricks)
        assertTrue(ball.dy > 0)
        assertTrue(ball.dx > 0)
        assertEquals(40.0, ball.y, 1e-9)
        assertEquals(listOf(BrickHitResult(4, 0, true, true, 10)), hits)
    }

    @ParameterizedTest
    @CsvSource(
        "130,40.5,0.2,-1,Y,40,1",
        "130,27.5,0.2,1,Y,28,-1",
        "120.5,34,1,0.3,X,120,-1",
        "148.5,34,-1,0.3,X,148,1",
    )
    fun `a brick hit from any side reflects on the axis of the smaller overlap and pushes the ball out`(
        x: Double,
        y: Double,
        dirX: Double,
        dirY: Double,
        axis: String,
        boundary: Double,
        signAfter: Double,
    ) {
        val ball = Ball(x, y, dirX, dirY)
        ball.move(1.0, grid(".....1......."))
        assertEquals(1, hits.size)
        if (axis == "X") {
            assertEquals(boundary, ball.x, 1e-9)
            assertTrue(ball.dx * signAfter > 0, "x direction flipped")
            assertEquals(Math.signum(dirY), Math.signum(ball.dy))
        } else {
            assertEquals(boundary, ball.y, 1e-9)
            assertTrue(ball.dy * signAfter > 0, "y direction flipped")
            assertEquals(Math.signum(dirX), Math.signum(ball.dx))
        }
    }

    @Test
    fun `only the brick with the largest overlap is hit in one sub-step`() {
        val bricks = grid("1111111111111")
        // x 26..30 straddles bricks at 4..28 (2 px) and 28..52 (2 px); slightly more in the right one
        val ball = Ball(26.4, 40.5, 0.0, -1.0)
        ball.move(1.0, bricks)
        assertEquals(1, hits.size)
        assertEquals(1, hits.single().column)
    }

    @Test
    fun `steel reflects the ball`() {
        val ball = Ball(10.0, 40.5, 0.0, -1.0)
        ball.move(1.0, grid("#............"))
        assertTrue(ball.dy > 0)
        assertEquals(listOf(BrickHitResult(0, 0, false, false, 0)), hits)
    }

    @Test
    fun `a ball cannot tunnel through a brick row at the maximum step length`() {
        // maximum speed 374.4 px/s is 6.24 px per step; from 40.1 one single move would end above the row
        val ball = Ball(100.0, 40.1, 0.0, -1.0)
        ball.move(6.24, grid("HHHHHHHHHHHHH"))
        assertEquals(1, hits.size)
        assertTrue(ball.y >= 40.0 - 1e-9, "ball at ${ball.y} is inside or above the row")
        assertTrue(ball.dy > 0)
    }

    @Test
    fun `a ball just below the paddle top is still caught`() {
        val ball = Ball(118.0, 222.0, 0.0, 1.0)
        assertEquals(1, ball.move(1.0))
        assertEquals(216.0, ball.y, 1e-9)
        assertTrue(ball.dy < 0)
    }

    @ParameterizedTest
    @CsvSource("1.0,0.0", "1.0,0.05", "-1.0,-0.1")
    fun `a nearly horizontal direction is clamped to 15 degrees`(
        dirX: Double,
        dirY: Double,
    ) {
        val ball = Ball(100.0, 100.0, dirX, dirY)
        ball.clampAngle()
        assertEquals(15.0, ball.degreesFromHorizontal(), 1e-9)
        assertEquals(dirX > 0, ball.dx > 0)
    }

    @Test
    fun `rotating keeps the minimum angle`() {
        val ball = Ball(0.0, 0.0, cos(Math.toRadians(20.0)), sin(Math.toRadians(20.0)))
        ball.rotate(-10.0)
        assertEquals(15.0, ball.degreesFromHorizontal(), 1e-9)
    }

    @Test
    fun `twenty bounces between steel and the top wall nudge the angle by five degrees`() {
        val bricks = grid("#############")
        val angle = Math.toRadians(15.0)
        val ball = Ball(100.0, 17.0, cos(angle), sin(angle))
        var seenBounces = 0
        var previous = 0
        var iterations = 0
        while (iterations++ < 5000) {
            ball.move(2.0, bricks)
            if (ball.bounces < previous) break
            if (ball.bounces > previous) seenBounces++
            previous = ball.bounces
            assertEquals(15.0, ball.degreesFromHorizontal(), 1e-6, "no nudge before the 20th bounce")
        }
        assertEquals(19, previous)
        assertEquals(19, seenBounces)
        assertEquals(20.0, ball.degreesFromHorizontal(), 1e-6)
    }

    @ParameterizedTest
    @ValueSource(strings = ["paddle", "tough brick"])
    fun `a paddle hit or a hit on a breakable brick resets the bounce count without a nudge`(scenario: String) {
        val steel = grid("#############")
        val angle = Math.toRadians(15.0)
        val ball = Ball(100.0, 17.0, cos(angle), sin(angle))
        while (ball.bounces < 19) ball.move(2.0, steel)
        if (scenario == "paddle") {
            ball.x = 118.0
            ball.y = 216.0
            ball.aim(0.0, 1.0)
            ball.move(1.0)
        } else {
            ball.x = 10.0
            ball.y = 40.5
            ball.aim(0.0, -1.0)
            ball.move(1.0, grid("T............"))
        }
        assertEquals(0, ball.bounces)
        assertEquals(90.0, ball.degreesFromHorizontal(), 1e-6)
    }
}
