package dev.sluide.games.minigolf

import dev.sluide.games.minigolf.geometry.Vec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class MiniGolfGameTest {
    /** Tee at (50, 125), cup at (150, 125), par 2: [HOLE_IN_ONE] drops at about 46 u/s. */
    private fun lane(
        number: Int = 1,
        build: HoleBuilder.() -> Unit = {},
    ) = testHole(number, par = 2) {
        tee(50, 125)
        cup(150, 125)
        build()
    }

    private fun game(vararg holes: Hole) = MiniGolfGame(holes.toList().ifEmpty { listOf(lane()) })

    /** Shoots and steps until the ball no longer rolls; returns every event, the Shot included. */
    private fun MiniGolfGame.play(
        angle: Double,
        power: Double,
    ): List<GameEvent> {
        assertTrue(shoot(angle, power), "shoot in $phase")
        val events = mutableListOf<GameEvent>()
        while (phase == Phase.ROLLING) events += step()
        return events
    }

    private fun MiniGolfGame.stepUntil(
        wanted: Phase,
        limit: Int = 2000,
    ): Int {
        var steps = 0
        while (phase != wanted && steps < limit) {
            step()
            steps++
        }
        assertEquals(wanted, phase, "after $steps steps")
        return steps
    }

    private fun MiniGolfGame.holeInTwo() {
        play(0.0, 0.1)
        play(0.0, 0.385)
    }

    @Test
    fun `a shot needs a resting ball, counts a stroke and is reported by the next step`() {
        val game = game()
        assertTrue(game.shoot(0.0, 2.0))
        assertEquals(1, game.strokes)
        assertEquals(Phase.ROLLING, game.phase)
        assertEquals(Vec(Rules.MAX_SPEED, 0.0), game.ballVelocity, "power is capped at 1")
        assertFalse(game.shoot(Math.PI, 0.5))
        assertEquals(1, game.strokes)
        assertEquals(GameEvent.Shot(1), game.step().first())
    }

    @Test
    fun `holing reports the strokes and the final score, hides the ball and counts toward the total`() {
        val game = game(lane(), lane(2))
        val events = game.play(0.0, HOLE_IN_ONE)
        assertEquals(listOf(GameEvent.Holed(1, true), GameEvent.HoleFinished(0, 1)), events.takeLast(2))
        assertEquals(Phase.HOLED, game.phase)
        assertFalse(game.ballVisible)
        assertEquals(listOf(1, null), game.scores)
        assertEquals(1, game.total)
        assertEquals(2, game.parSoFar)
        assertEquals(-1, game.toPar)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `the card follows 60 steps after the drop, 90 after a hole in one`(holeInOne: Boolean) {
        val game = game()
        if (holeInOne) game.play(0.0, HOLE_IN_ONE) else game.holeInTwo()
        assertEquals(if (holeInOne) 1 else 2, game.scores[0])
        val steps = game.stepUntil(Phase.CARD)
        assertEquals(if (holeInOne) Rules.HOLE_IN_ONE_STEPS else Rules.HOLED_STEPS, steps)
    }

    @Test
    fun `the card stops the clock until continue starts the next hole afresh, and the last card ends the round`() {
        val game = game(lane { rotatingBar(250 to 60, 30, 240) }, lane(2))
        game.play(0.0, 0.3)
        game.play(Math.PI, 0.3) // back to the tee
        game.holeInTwo()
        game.stepUntil(Phase.CARD)
        val clock = game.stepCount
        val bar = game.moverSegments()
        repeat(10) { assertEquals(emptyList<GameEvent>(), game.step()) }
        assertEquals(clock, game.stepCount)
        assertEquals(bar, game.moverSegments())

        assertEquals(emptyList<GameEvent>(), game.continueToNext())
        assertEquals(1, game.holeIndex)
        assertEquals(Phase.AIMING, game.phase)
        assertEquals(0, game.strokes)
        assertEquals(0L, game.stepCount)
        assertEquals(emptyList<Vec>(), game.ghostPath)
        assertEquals(Vec(50.0, 125.0), game.ball)
        assertTrue(game.ballVisible)

        game.play(0.0, HOLE_IN_ONE)
        assertEquals(emptyList<GameEvent>(), game.continueToNext(), "not before the card")
        game.stepUntil(Phase.CARD)
        assertEquals(listOf(GameEvent.RoundFinished(5)), game.continueToNext())
        assertEquals(Phase.FINISHED, game.phase)
        assertEquals(listOf(4, 1), game.scores)
        assertEquals(1, game.toPar)
        assertEquals(emptyList<GameEvent>(), game.step())
        assertEquals(emptyList<GameEvent>(), game.continueToNext())
        assertEquals(Phase.FINISHED, game.phase)
    }

    @Test
    fun `water costs a stroke and puts the ball back where the shot was played from, at rest`() {
        val game = game(lane { water(rect(100, 100, 120, 150)) })
        game.play(0.0, 0.1)
        val from = game.ball
        assertTrue(from.x > 55, "moved off the tee: $from")
        val events = game.play(0.0, 0.5)
        assertTrue(GameEvent.Splash in events, "$events")
        assertEquals(2 + 1, game.strokes)
        assertEquals(from, game.ball)
        assertTrue(game.ballVelocity.isZero)
        assertEquals(Phase.AIMING, game.phase)
        assertEquals(100.0, game.ghostPath.last().x, 1.0, "the ghost ends at the water")
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `the ball is picked up when the stroke count reaches 7 without holing, water included`(splash: Boolean) {
        val game = game(lane { water(rect(30, 20, 40, 230)) })
        // Six putts of 0.3 units, then either a seventh or a splash on the sixth (7 strokes with the penalty).
        repeat(if (splash) 5 else 6) { game.play(0.0, 0.02) }
        assertEquals(Phase.AIMING, game.phase)
        val events = if (splash) game.play(Math.PI, 0.3) else game.play(0.0, 0.02)
        assertEquals(listOf(GameEvent.PickedUp, GameEvent.HoleFinished(0, 8)), events.takeLast(2))
        assertEquals(7, game.strokes)
        assertEquals(listOf(8), game.scores)
        assertEquals(Phase.HOLED, game.phase)
        assertFalse(game.ballVisible)
        assertEquals(Rules.HOLED_STEPS, game.stepUntil(Phase.CARD))
    }

    @Test
    fun `a roll that does not end in 20 seconds stops where it is`() {
        // The whole green slopes down: the ball leans on the bottom rail, never resting.
        val game = game(lane { slope(rect(10, 20, 310, 230), 0 to 50) })
        game.shoot(0.0, 0.0)
        repeat(Rules.ROLL_LIMIT_STEPS - 1) { game.step() }
        assertEquals(Phase.ROLLING, game.phase)
        game.step()
        assertEquals(Phase.AIMING, game.phase)
        assertTrue(game.ballVelocity.isZero)
        assertEquals(230 - Rules.BALL_RADIUS, game.ball.y, 0.5)
        assertEquals(1, game.strokes)
    }

    @Test
    fun `a rotating bar pushes a resting ball into a roll that costs no stroke and leaves no ghost`() {
        val game = game(lane { rotatingBar(160 to 125, 40, 240) })
        val start = Vec(175.0, 135.0)
        game.placeBall(start)
        game.step()
        assertEquals(Phase.AIMING, game.phase)
        val events = mutableListOf<GameEvent>()
        repeat(30) { if (game.phase == Phase.AIMING) events += game.step() }
        assertEquals(Phase.ROLLING, game.phase, "swept by the bar")
        game.stepUntil(Phase.AIMING)
        assertTrue(game.ball.distanceTo(start) > 3, "pushed away to ${game.ball}")
        assertEquals(0, game.strokes)
        assertFalse(events.any { it is GameEvent.Shot })
        assertEquals(emptyList<Vec>(), game.ghostPath)
    }

    @Test
    fun `the ghost is the previous shot, sampled every third step, kept until the next shot ends`() {
        val game = game()
        assertEquals(emptyList<Vec>(), game.ghostPath)
        game.shoot(0.0, 0.3)
        var steps = 0
        while (game.phase == Phase.ROLLING) {
            game.step()
            steps++
            if (game.phase == Phase.ROLLING) assertEquals(emptyList<Vec>(), game.ghostPath, "not during the shot")
        }
        val first = game.ghostPath
        assertEquals(Vec(50.0, 125.0), first.first())
        assertEquals(game.ball, first.last())
        assertEquals(2 + (steps - 1) / Rules.GHOST_SAMPLE_STEPS, first.size)
        assertEquals(1.5 * 3, first[1].x - first[0].x, 0.5, "about 90 u/s · 3 steps")

        game.shoot(Math.PI, 0.1)
        game.step()
        assertEquals(first, game.ghostPath)
        game.stepUntil(Phase.AIMING)
        assertEquals(game.ball, game.ghostPath.last())
        assertTrue(game.ghostPath.first().x > 100)
    }

    @Test
    fun `two games given the same shots share no state and play the same round`() {
        val hole =
            lane {
                bumper(200, 90, 8)
                slope(rect(120, 150, 220, 200), 30 to -50)
                rotatingBar(230 to 160, 50, 200)
                tunnel(280 to 200, 0 to -1, 60 to 60, 1 to 1)
            }

        fun trace(): List<Any> {
            val game = game(hole)
            val trace = mutableListOf<Any>()
            listOf(0.4 to 0.9, 2.5 to 0.7, 5.0 to 1.0, 1.0 to 0.4).forEachIndexed { index, (angle, power) ->
                repeat(index * 7) { trace.addAll(game.step()) }
                if (game.shoot(angle, power)) {
                    while (game.phase == Phase.ROLLING) {
                        trace.addAll(game.step())
                        trace.add(game.ball)
                    }
                }
            }
            return trace
        }
        val first = trace()
        assertTrue(first.size > 100)
        assertEquals(first, trace())
    }

    @Test
    fun `water after a push costs no stroke and puts the ball back where it rested`() {
        val start = Vec(175.0, 135.0)
        val game =
            game(
                lane {
                    rotatingBar(160 to 125, 40, 240)
                    water(rect(150, 148, 190, 170))
                },
            )
        game.placeBall(start)
        game.step()
        val events = mutableListOf<GameEvent>()
        while (GameEvent.Splash !in events && game.stepCount < 120) events += game.step()
        assertTrue(GameEvent.Splash in events, "swept into the water")
        assertEquals(0, game.strokes)
        assertEquals(start, game.ball)
        assertEquals(Phase.AIMING, game.phase)
    }

    @Test
    fun `a weak push into the water costs no stroke either`() {
        // The slow block pushes the resting ball by less than the rest speed, over the water's edge at 107.6.
        val start = Vec(105.0 + Rules.BALL_RADIUS + 0.0001, 125.0)
        val game =
            game(
                lane {
                    slidingBlock(100 to 125, 160 to 125, 10, 30, 1200)
                    water(rect(107.6, 100, 130, 150))
                },
            )
        game.placeBall(start)
        val events = mutableListOf<GameEvent>()
        while (GameEvent.Splash !in events && game.stepCount < 60) {
            events += game.step()
            assertTrue(game.phase != Phase.ROLLING || game.stepCount == 1L, "never rolls")
        }
        assertTrue(GameEvent.Splash in events)
        assertEquals(0, game.strokes)
        assertTrue(game.ball.x in start.x..107.6, "back where it rested: ${game.ball}")
    }

    @ParameterizedTest
    @ValueSource(doubles = [134.0, 135.0])
    fun `a sliding block that squeezes a ball against a rail never holds it inside`(lowest: Double) {
        val block = SlidingBlock(Vec(160.0, 118.0), Vec(160.0, lowest), 10.0, 10.0, 120)
        val hole =
            hole(1, par = 3) {
                green(rect(10, 110, 310, 140))
                tee(30, 125)
                cup(290, 125)
                slidingBlock(160 to 118, 160 to lowest, 10, 10, 120)
            }
        val game = MiniGolfGame(listOf(hole))
        game.placeBall(Vec(160.0, 137.5))
        repeat(600) {
            game.step()
            assertFalse(
                block.covers(game.ball, game.stepCount),
                "inside the block at ${game.ball}, step ${game.stepCount}",
            )
            assertTrue(game.ball.y in 110.0..140.0, "on the green: ${game.ball}")
        }
        assertEquals(Phase.AIMING, game.phase)
    }

    @Test
    fun `a ball put back after water is moved out of a sliding block that now covers the spot`() {
        val block = SlidingBlock(Vec(140.0, 125.0), Vec(180.0, 125.0), 30.0, 28.0, 120)
        val hole =
            testHole {
                slidingBlock(140 to 125, 180 to 125, 30, 28, 120)
                water(rect(220, 20, 240, 230))
            }
        val game = MiniGolfGame(listOf(hole))
        val start = Vec(175.0, 125.0)
        game.placeBall(start)
        game.step()
        val events = game.play(0.0, 0.3)
        assertTrue(GameEvent.Splash in events)
        assertTrue(block.covers(start, game.stepCount), "the block covers the shot's start now")
        assertFalse(block.covers(game.ball, game.stepCount), "inside the block at ${game.ball}")
        assertEquals(2, game.strokes)
    }

    /** Whether the centre of a ball at [p] is inside the block at [step]. */
    private fun SlidingBlock.covers(
        p: Vec,
        step: Long,
    ): Boolean {
        val center = centerAt(step)
        return Math.abs(p.x - center.x) < width / 2 && Math.abs(p.y - center.y) < height / 2
    }

    private companion object {
        const val HOLE_IN_ONE = 0.39
    }
}
