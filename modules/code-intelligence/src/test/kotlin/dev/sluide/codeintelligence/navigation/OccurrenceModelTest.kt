package dev.sluide.codeintelligence.navigation

import dev.sluide.fileviewers.StyledRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** When the occurrences of the name at the caret are asked, shown and cleared. */
class OccurrenceModelTest {
    private var canAsk = true
    private val asked = mutableListOf<Int>()
    private val answers = mutableListOf<(List<StyledRange>) -> Unit>()
    private var dropped = 0
    private val shown = mutableListOf<List<StyledRange>>()
    private val timers = mutableListOf<Pair<Duration, () -> Unit>>()

    /** Words of three characters and a space below 100: [0, 3), [4, 7), …; a caret at a word's end takes it too. */
    private val host =
        object : OccurrenceHost {
            override fun wordAt(offset: Int) = if (offset < 100) (offset / 4 * 4) until (offset / 4 * 4 + 3) else null

            override fun canAsk() = canAsk

            override fun ask(
                offset: Int,
                done: (List<StyledRange>) -> Unit,
            ): AutoCloseable {
                asked += offset
                answers += done
                return AutoCloseable { dropped++ }
            }

            override fun show(ranges: List<StyledRange>) {
                shown += ranges
            }
        }

    private val model =
        OccurrenceModel(host, 300.milliseconds) { delay, action ->
            val timer = delay to action
            timers += timer
            AutoCloseable { timers -= timer }
        }

    private fun elapse() = timers.toList().also { timers.clear() }.forEach { (_, action) -> action() }

    private val ranges = listOf(StyledRange(0, 4, "ci-occurrence-read"), StyledRange(20, 24, "ci-occurrence-write"))

    @Test
    fun `the caret resting on a word asks after the delay, and the answer shows`() {
        model.caretMoved(2)
        assertEquals(listOf(300.milliseconds), timers.map { it.first })
        assertEquals(emptyList(), asked, "not before the delay")
        elapse()
        assertEquals(listOf(2), asked)
        answers.single()(ranges)
        assertEquals(listOf(ranges), shown)
    }

    @Test
    fun `moving within the word keeps it, leaving it clears and drops the question, a new word asks again`() {
        model.caretMoved(1)
        elapse()
        answers.single()(ranges)
        model.caretMoved(3)
        assertEquals(1, shown.size, "still on the word")
        assertEquals(emptyList(), timers)

        model.caretMoved(6)
        assertEquals(emptyList(), shown.last(), "cleared on leaving the word")
        elapse()
        model.caretMoved(150)
        assertEquals(1, dropped, "the question for the second word is not wanted any more")
        assertEquals(emptyList(), timers)
        assertEquals(listOf(1, 6), asked)
    }

    @Test
    fun `a move before the delay ends asks only for where the caret came to rest`() {
        model.caretMoved(1)
        model.caretMoved(9)
        elapse()
        assertEquals(listOf(9), asked)
    }

    @Test
    fun `an edit keeps what shows and asks again for the word at the caret`() {
        model.caretMoved(1)
        elapse()
        answers.single()(ranges)
        model.textChanged(2)
        assertEquals(listOf(ranges), shown, "the layer follows the edit until the new answer")
        model.caretMoved(2)
        assertEquals(1, timers.size, "the caret move of the edit asks no second time")
        elapse()
        answers.last()(emptyList())
        assertEquals(emptyList(), shown.last())
    }

    @Test
    fun `nothing is asked while the server cannot be, the next move in the word asks again`() {
        canAsk = false
        model.caretMoved(1)
        elapse()
        assertEquals(emptyList(), asked)
        canAsk = true
        model.caretMoved(2)
        elapse()
        assertEquals(listOf(2), asked)
    }

    @Test
    fun `a caret at a word's end asks at its start`() {
        model.caretMoved(7)
        elapse()
        assertEquals(listOf(4), asked)
    }
}
