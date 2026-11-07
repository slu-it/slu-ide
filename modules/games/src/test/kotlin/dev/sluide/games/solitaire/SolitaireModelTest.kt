package dev.sluide.games.solitaire

import dev.sluide.games.solitaire.solver.Deal
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Dealing: the first deal, the deal fetched in advance, modes, Deal Number… and failures. */
internal class SolitaireModelTest : SolitaireModelFixture() {
    @ParameterizedTest
    @EnumSource(Mode::class)
    fun `the first deal is made in the background, then the next one is fetched in advance`(mode: Mode) {
        val model = model(mode)

        model.ensureGame()
        model.ensureGame()

        assertTrue(model.isDealing)
        assertNull(model.game)
        assertFalse(model.inputEnabled)
        assertEquals("Dealing…", model.statusText)
        assertEquals(1, dealQueue.size)

        dealQueue.removeFirst().run()

        val game = assertNotNull(model.game)
        assertEquals(mode to 100, game.mode to game.number)
        assertEquals(listOf<TableEvent>(TableEvent.Dealt(game)), events)
        assertFalse(model.isDealing)
        assertTrue(model.inputEnabled)
        assertEquals("Deal #100", model.statusText)
        assertEquals(1, dealQueue.size)

        runDealing()

        assertEquals(listOf(mode to 100, mode to 200), searches.map { it.mode to it.number })
        assertEquals(100, model.game?.number)
    }

    @Test
    fun `New Game plays the deal fetched in advance at once and resets the clock`() {
        val model = readyModel()
        now = 5_000

        model.newGame()

        assertEquals(200, model.game?.number)
        assertEquals(0, model.elapsedMillis)
        assertEquals(listOf<TableEvent>(TableEvent.Dealt(assertNotNull(model.game))), events)
        assertEquals(1, dealQueue.size)
        runDealing()
        assertEquals(listOf(100, 200, 300), searches.map { it.number })
    }

    @Test
    fun `New Game while the next deal is fetched waits for it, the previous game staying without input`() {
        val model = model()
        model.ensureGame()
        dealQueue.removeFirst().run()

        model.newGame()

        assertTrue(model.isDealing)
        assertEquals(100, model.game?.number)
        assertFalse(model.inputEnabled)
        assertFalse(model.play(Move.Draw))
        assertEquals("Dealing…", model.statusText)
        runDealing()
        assertEquals(200, model.game?.number)
        assertEquals(listOf(100, 200, 300), searches.map { it.number })
    }

    @Test
    fun `New Game waiting for the next deal searches itself when that one fails`() {
        val model = model()
        model.ensureGame()
        dealQueue.removeFirst().run()
        findable = { it != 200 }

        model.newGame()
        runDealing()

        assertEquals(300, model.game?.number)
        assertFalse(model.isDealing)
    }

    @Test
    fun `a mode change cancels the deal under way and drops its result`() {
        val model = model()
        model.ensureGame()

        model.chooseMode(Mode.FREECELL)
        runDealing()

        assertEquals(Mode.KLONDIKE_DRAW_1 to 100, searches[0].mode to searches[0].number)
        assertTrue(searches[0].cancelled())
        assertEquals(Mode.FREECELL, model.game?.mode)
        assertEquals(listOf(Mode.FREECELL), events.filterIsInstance<TableEvent.Dealt>().map { it.game.mode })
        assertEquals(Mode.FREECELL, preferences.lastMode)
        assertEquals(Mode.FREECELL, model.mode)
    }

    @Test
    fun `a mode change stops the prefetch of the old mode and fetches one of the new mode`() {
        val model = model()
        model.ensureGame()
        dealQueue.removeFirst().run()

        model.chooseMode(Mode.FREECELL)
        runDealing()

        val klondike = Mode.KLONDIKE_DRAW_1
        assertEquals(
            listOf(klondike to 100, klondike to 200, Mode.FREECELL to 300, Mode.FREECELL to 400),
            searches.map { it.mode to it.number },
        )
        assertTrue(searches[1].cancelled())
        assertEquals(Mode.FREECELL to 300, model.game?.let { it.mode to it.number })
        model.newGame()
        assertEquals(Mode.FREECELL to 400, model.game?.let { it.mode to it.number })
    }

    @Test
    fun `a deal fetched in advance for another mode is never played`() {
        val model = readyModel()

        model.chooseVariant(Variant.SPIDER)
        model.chooseVariant(Variant.KLONDIKE)
        runDealing()

        assertEquals(Mode.KLONDIKE_DRAW_1 to 400, model.game?.let { it.mode to it.number })
        assertFalse(events.any { it is TableEvent.Dealt && it.game.mode == Mode.SPIDER_1 })
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `a failed first deal says so and F2 tries again`(throws: Boolean) {
        if (throws) failure = StackOverflowError() else findable = { false }
        val model = model()

        model.ensureGame()
        runDealing()

        assertTrue(model.isFailed)
        assertNull(model.game)
        assertFalse(model.inputEnabled)
        assertEquals("Could not deal a game — F2 to try again", model.statusText)

        failure = null
        findable = { true }
        model.newGame()
        assertEquals("Dealing…", model.statusText)
        runDealing()
        assertFalse(model.isFailed)
        assertNotNull(model.game)
    }

    @Test
    fun `a failed New Game keeps the current game playable`() {
        val model = model()
        model.ensureGame()
        dealQueue.removeFirst().run()
        findable = { false }
        runDealing()

        model.newGame()
        runDealing()

        assertEquals(100, model.game?.number)
        assertFalse(model.isFailed)
        assertTrue(model.inputEnabled)
        assertEquals("Could not deal a game — F2 to try again", model.statusText)
        model.play(Move.Draw)
        assertEquals("Deal #100", model.statusText)
    }

    @Test
    fun `a requested deal that is winnable is played and the deal fetched in advance stays`() {
        val model = readyModel()

        model.requestDeal(777) { _, _ -> fail("asked for a winnable deal") }

        assertEquals("Looking for deal #777…", model.statusText)
        assertFalse(model.inputEnabled)
        runDealing()
        assertEquals(777, model.game?.number)
        model.newGame()
        assertEquals(200, model.game?.number)
    }

    @ParameterizedTest
    @CsvSource("777, 778, true", "777, 778, false", "1000000, 1, true")
    fun `a requested deal that is not winnable offers the next winnable one`(
        number: Int,
        alternative: Int,
        accept: Boolean,
    ) {
        val model = readyModel()
        winnable = { it != number }
        val asked = mutableListOf<Pair<Int, Int>>()

        model.requestDeal(number) { n, alt ->
            asked += n to alt
            accept
        }
        runDealing()

        assertEquals(listOf(number to alternative), asked)
        assertEquals(if (accept) alternative else 100, model.game?.number)
        assertFalse(model.isDealing)
        assertTrue(model.inputEnabled)
        assertEquals("Deal #${model.game?.number}", model.statusText)
    }

    @Test
    fun `a newer request while the alternative is offered wins`() {
        val model = readyModel()
        winnable = { it != 777 }

        model.requestDeal(777) { _, _ ->
            model.newGame()
            true
        }
        runDealing()

        assertEquals(200, model.game?.number)
        assertFalse(model.isDealing)
        assertEquals(listOf(200), events.filterIsInstance<TableEvent.Dealt>().map { it.game.number })
    }

    @Test
    fun `the clock of the game on the table holds while a deal is looked for and offered`() {
        val model = readyModel()
        now = 5_000
        winnable = { false }

        model.requestDeal(777) { _, _ ->
            now = 30_000
            false
        }
        now = 20_000
        runDealing()

        assertEquals(5_000, model.elapsedMillis)
        now = 31_000
        assertEquals(6_000, model.elapsedMillis)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, Deals.MAX_NUMBER + 1])
    fun `a deal number out of range is refused`(number: Int) {
        val model = readyModel()

        assertFailsWith<IllegalArgumentException> { model.requestDeal(number) { _, _ -> false } }
        assertFalse(model.isDealing)
    }

    @Test
    fun `without a winnable deal near a requested one the game goes on, or the failure shows without one`() {
        val model = readyModel()
        winnable = { false }
        findable = { false }

        model.requestDeal(777) { _, _ -> fail("asked without an alternative") }
        runDealing()

        assertEquals(100, model.game?.number)
        assertTrue(model.inputEnabled)
        assertEquals("No winnable deal found near #777", model.statusText)

        val empty = model()
        empty.requestDeal(777) { _, _ -> fail("asked without an alternative") }
        runDealing()
        assertTrue(empty.isFailed)
        assertEquals("Could not deal a game — F2 to try again", empty.statusText)
    }
}
