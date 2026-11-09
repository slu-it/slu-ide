package dev.sluide.games.minigolf.ui

import dev.sluide.games.minigolf.MiniGolfGame
import dev.sluide.games.minigolf.geometry.Vec
import dev.sluide.games.minigolf.testHole
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BurstStateTest {
    private fun round() = MiniGolfGame(List(2) { testHole(it + 1) })

    private fun MiniGolfGame.stepTimes(count: Int) = repeat(count) { step() }

    @Test
    fun `the burst shows on the hole it began on`() {
        val game = round()
        game.stepTimes(5)
        val state = BurstState()

        state.start(game, Vec(290.0, 125.0))

        assertTrue(state.isActive(game))
        game.stepTimes(10)
        assertTrue(state.isActive(game))
    }

    @Test
    fun `the next hole reaching the same step count does not replay it`() {
        val game = round()
        game.stepTimes(5)
        val state = BurstState()
        state.start(game, Vec(290.0, 125.0))

        game.startAt(1)
        game.stepTimes(5)

        assertFalse(state.isActive(game))
    }

    @Test
    fun `a new round on hole 1 at the same step count does not replay it either`() {
        val game = round()
        game.stepTimes(5)
        val state = BurstState()
        state.start(game, Vec(290.0, 125.0))

        val next = round()
        next.stepTimes(5)

        assertFalse(state.isActive(next))
    }
}
