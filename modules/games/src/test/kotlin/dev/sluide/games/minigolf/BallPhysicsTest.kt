package dev.sluide.games.minigolf

import dev.sluide.games.minigolf.geometry.Vec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

internal class BallPhysicsTest {
    /** Rolls a ball from [from] at [velocity] until the roll ends; returns the ball and how it ended. */
    private fun roll(
        hole: Hole,
        from: Vec,
        velocity: Vec,
        maxSteps: Int = 2000,
    ): Pair<Ball, RollEnd> {
        val physics = BallPhysics(hole)
        val ball = Ball(from, velocity)
        for (step in 1..maxSteps) {
            val end = physics.roll(ball, step.toLong())
            if (end != RollEnd.NONE) return ball to end
        }
        return ball to RollEnd.NONE
    }

    @ParameterizedTest
    @CsvSource(
        "false,119", // felt: v² / (2 · 60), less v · dt / 2 for whole steps
        "true,23", // sand: v² / (2 · 300), less v · dt / 2
    )
    fun `a ball stops after about v squared over twice the deceleration of its surface`(
        sand: Boolean,
        expected: Double,
    ) {
        val hole = testHole { if (sand) sand(rect(10, 20, 310, 230)) }
        val (ball, end) = roll(hole, Vec(40.0, 125.0), Vec(120.0, 0.0))
        assertEquals(RollEnd.REST, end)
        assertEquals(expected, ball.position.x - 40.0, 0.5)
        assertTrue(ball.velocity.isZero)
    }

    @Test
    fun `a diagonal wall reflects the normal part at 0,75 and keeps the part along it`() {
        // The wall x + y = 300; the ball comes from the left along y = 150, just short of it.
        val hole = testHole { wall(100 to 200, 200 to 100) }
        val physics = BallPhysics(hole)
        val gap = Rules.BALL_RADIUS + 0.5
        val ball = Ball(Vec(150.0 - gap * Math.sqrt(2.0), 150.0), Vec(100.0, 0.0))
        physics.roll(ball, 1)
        val normal = Vec(-1.0, -1.0).normalized()
        val along = Vec(1.0, -1.0).normalized()
        assertEquals(0.75, (ball.velocity dot normal) / (ball.velocity dot along), 1e-9)
        assertTrue(ball.position.x + ball.position.y <= 300 - Rules.BALL_RADIUS * Math.sqrt(2.0) + 1e-9, "pushed out")
    }

    @ParameterizedTest
    @CsvSource(
        "100,113.85", // (100 - 1 friction) · 1.15
        "290,300", // capped
    )
    fun `a bumper sends a ball back faster, at most at the top speed`(
        speed: Double,
        expected: Double,
    ) {
        val hole = testHole { bumper(160, 125, 6) }
        val ball = Ball(Vec(160.0 - 6 - Rules.BALL_RADIUS - 0.5, 125.0), Vec(speed, 0.0))
        BallPhysics(hole).roll(ball, 1)
        assertEquals(-expected, ball.velocity.x, 1e-9)
        assertEquals(0.0, ball.velocity.y, 1e-9)
    }

    @Test
    fun `a ball slow enough drops into the cup`() {
        val (ball, end) = roll(testHole { cup(200, 125) }, Vec(185.0, 125.0), Vec(80.0, 0.0))
        assertEquals(RollEnd.HOLED, end)
        assertTrue(ball.position.distanceTo(Vec(200.0, 125.0)) <= Rules.CUP_RADIUS)
    }

    @Test
    fun `a fast ball lips out once, turned toward the centre and 30 percent slower`() {
        val physics = BallPhysics(testHole { cup(200, 125) })
        val ball = Ball(Vec(150.0, 127.0), Vec(250.0, 0.0))
        var step = 0L
        while (!ball.overCup) {
            val before = ball.velocity.length
            assertEquals(RollEnd.NONE, physics.roll(ball, ++step))
            if (ball.overCup) {
                assertEquals((before - 1) * Rules.LIP_OUT_SPEED_FACTOR, ball.velocity.length, 1e-9)
                assertEquals(-Rules.LIP_OUT_TURN, ball.velocity.angle(), 1e-9, "turned up, toward the cup at y 125")
            }
        }
        // Over the cup at about 174 u/s: only friction slows it until it has passed, and it never drops.
        while (ball.overCup) {
            val before = ball.velocity.length
            assertEquals(RollEnd.NONE, physics.roll(ball, ++step))
            assertEquals(before - 1, ball.velocity.length, 1e-9)
        }
        var end = RollEnd.NONE
        while (end == RollEnd.NONE) end = physics.roll(ball, ++step)
        assertEquals(RollEnd.REST, end)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a tunnel sends the ball out of the other mouth at the same speed`(intoA: Boolean) {
        val a = Vec(100.0, 125.0)
        val b = Vec(250.0, 60.0)
        val hole = testHole { tunnel(100 to 125, -1 to 0, 250 to 60, 0 to 1) }
        val physics = BallPhysics(hole)
        val (entry, exit, exitDirection) = if (intoA) Triple(a, b, Vec(0.0, 1.0)) else Triple(b, a, Vec(-1.0, 0.0))
        val ball = Ball(entry - Vec(5.5, 0.0), Vec(60.0, 0.0))
        physics.roll(ball, 1)
        assertEquals(59.0, ball.velocity.length, 1e-9, "only friction")
        assertEquals(exitDirection.angle(), ball.velocity.angle(), 1e-9)
        assertTrue(ball.position.distanceTo(exit) < 1.0, "at the other mouth: ${ball.position}")
    }

    @Test
    fun `a ball cannot go back into the mouth it came out of until it has left it`() {
        val hole = testHole { tunnel(100 to 125, -1 to 0, 250 to 60, 0 to 1) }
        val physics = BallPhysics(hole)
        val ball = Ball(Vec(94.5, 125.0), Vec(60.0, 0.0))
        physics.roll(ball, 1)
        // Out of b and turned straight back while still in its mouth: it stays at b.
        ball.velocity = Vec(0.0, -40.0)
        physics.roll(ball, 2)
        assertTrue(ball.position.distanceTo(Vec(250.0, 60.0)) < Rules.TUNNEL_RADIUS, "still at b: ${ball.position}")
        // Once it has left the mouth, it may enter it again.
        ball.position = Vec(250.0, 72.0)
        ball.velocity = Vec(0.0, -40.0)
        var step = 3L
        while (ball.position.y > 40 && ball.position.x > 200 && step < 100) physics.roll(ball, step++)
        assertTrue(ball.position.distanceTo(Vec(100.0, 125.0)) < 10.0, "through to a: ${ball.position}")
    }

    @Test
    fun `a slope pulls a resting ball, less the slope friction, and lets it rest only on flat felt`() {
        val hole = testHole { slope(rect(100, 40, 200, 160), 0 to 50) }
        val physics = BallPhysics(hole)
        val ball = Ball(Vec(150.0, 50.0))
        repeat(60) { assertEquals(RollEnd.NONE, physics.roll(ball, it.toLong())) }
        assertEquals(50.0 - Rules.SLOPE_DECELERATION, ball.velocity.y, 0.5)
        assertEquals(0.0, ball.velocity.x)
        var end = RollEnd.NONE
        var step = 60L
        while (end == RollEnd.NONE) end = physics.roll(ball, step++)
        assertEquals(RollEnd.REST, end)
        assertTrue(ball.position.y > 160, "rests below the slope: ${ball.position}")
    }

    @ParameterizedTest
    @ValueSource(doubles = [0.0, 0.3])
    fun `a ball at top speed does not pass through a thin wall`(angle: Double) {
        val hole = testHole { wall(160 to 20, 160 to 230) }
        val physics = BallPhysics(hole)
        // Off the grid of whole steps, so that a ball moving 5 units a step would land just beyond the wall.
        val ball = Ball(Vec(151.3, 125.0), Vec.ofAngle(angle, Rules.MAX_SPEED))
        repeat(120) {
            physics.roll(ball, it.toLong())
            assertTrue(ball.position.x <= 160 - Rules.BALL_RADIUS + 1e-9, "left of the wall: ${ball.position}")
        }
    }

    @Test
    fun `water ends the roll`() {
        val (ball, end) = roll(testHole { water(rect(150, 100, 200, 150)) }, Vec(100.0, 125.0), Vec(150.0, 0.0))
        assertEquals(RollEnd.SPLASH, end)
        assertEquals(150.0, ball.position.x, 1.0)
    }

    @Test
    fun `a moving block hits a resting ball with its own speed, reflected at 0,75`() {
        val block = SlidingBlock(Vec(100.0, 125.0), Vec(160.0, 125.0), 10.0, 30.0, 120)
        val hole = testHole { slidingBlock(100 to 125, 160 to 125, 10, 30, 120) }
        val physics = BallPhysics(hole)
        // The right edge is at 133.4 at step 29 and at 135 at step 30: the ball at 136 is out of reach, then in.
        val ball = Ball(Vec(136.0, 125.0))
        assertFalse(physics.push(ball, 29))
        assertEquals(Vec(136.0, 125.0), ball.position)
        assertTrue(physics.push(ball, 30))
        val surface = (block.centerAt(30).x - block.centerAt(29).x) * Rules.STEPS_PER_SECOND
        assertEquals((1 + Rules.WALL_RESTITUTION) * surface, ball.velocity.x, 1e-9)
        assertEquals(135.0 + Rules.BALL_RADIUS, ball.position.x, 1e-9)
    }

    @Test
    fun `a moving block that only touches a ball moves it without making it roll`() {
        // At step 1 the slow block has moved 0.0004 units, at 0.025 u/s, far below the rest speed.
        val hole = testHole { slidingBlock(100 to 125, 160 to 125, 10, 30, 1200) }
        val physics = BallPhysics(hole)
        val start = 105.0 + Rules.BALL_RADIUS + 0.0001
        val ball = Ball(Vec(start, 125.0))
        assertFalse(physics.push(ball, 1))
        assertTrue(ball.velocity.isZero)
        assertNotEquals(start, ball.position.x, "moved out of the block")
    }

    @Test
    fun `a ball that comes to rest over the cup drops, even after a lip-out`() {
        val physics = BallPhysics(testHole { cup(200, 125) })
        val ball = Ball(Vec(202.0, 125.0), Vec(1.5, 0.0)).apply { overCup = true }
        assertEquals(RollEnd.HOLED, physics.roll(ball, 1))
    }

    @Test
    fun `a ball put back into a tunnel mouth cannot fall into it on the next shot`() {
        val physics = BallPhysics(testHole { tunnel(100 to 125, -1 to 0, 250 to 60, 0 to 1) })
        val ball = Ball(Vec(251.0, 61.0))
        physics.settle(ball, 1)
        assertEquals(Vec(250.0, 60.0), ball.blockedMouth)
        ball.velocity = Vec(0.0, 60.0)
        physics.roll(ball, 2)
        assertTrue(ball.position.distanceTo(Vec(251.0, 62.0)) < 0.1, "still at that mouth: ${ball.position}")
    }
}
