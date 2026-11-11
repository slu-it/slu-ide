package dev.sluide.speech

import dev.sluide.fileviewers.ViewActivity
import dev.sluide.speech.editor.FakeControls
import dev.sluide.speech.session.ReadingPosition
import dev.sluide.speech.session.ReadingState
import dev.sluide.speech.ui.FakeHost
import dev.sluide.speech.ui.MODEL_MISSING
import dev.sluide.speech.ui.ReadAloudModel
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadAloudControlsTest {
    private class FakeSurface : ReadAloudSurface {
        var text: ReadingText? = ReadingText("One. Two. Three.", 0, 16)
        var asked = 0
        val calls = mutableListOf<String>()

        override fun readingText(): ReadingText? {
            asked++
            return text
        }

        override fun lock(locked: Boolean) {
            calls += "lock $locked"
        }

        override fun highlight(
            sentence: IntRange?,
            word: IntRange?,
        ) {
            calls += "highlight $sentence $word"
        }

        override fun reveal(
            start: Int,
            end: Int,
        ) {
            calls += "reveal $start $end"
        }
    }

    private val host = FakeHost()
    private val model = ReadAloudModel(host, ownsText = false)
    private val surface = FakeSurface()
    private val fakeControls = FakeControls()
    private var toolbarPlay: () -> Unit = {}
    private val controls =
        ReadAloudControls(model, surface) { play ->
            toolbarPlay = play
            fakeControls
        }

    private fun speaking() {
        controls.play()
        host.reading.emit(ReadingState.SPEAKING)
    }

    private fun key(
        code: KeyCode,
        shortcut: Boolean = false,
    ): Boolean {
        val meta = System.getProperty("os.name").startsWith("Mac")
        return controls.handleKey(
            KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut && !meta, false, shortcut && meta),
        )
    }

    @Test
    fun `the toolbar's Play asks the surface for its text and reads it`() {
        surface.text = ReadingText("One. Two. Three.", 6, 9)

        toolbarPlay()

        assertEquals(1, surface.asked)
        assertEquals(listOf("One.", "Two."), host.reading.chunks.map { it.text })
        assertEquals(1, host.reading.startChunk)
        assertTrue(controls.reading)
    }

    @Test
    fun `without a text Play reads nothing and says so`() {
        surface.text = null

        controls.play()

        assertTrue(host.readings.isEmpty())
        assertEquals("Nothing to read.", fakeControls.shown.last().status)
    }

    @Test
    fun `lock, highlight and reveal go to the surface only when they change`() {
        speaking()
        host.reading.position = ReadingPosition(0, 0, 4, 0, 3)
        host.pollTick?.run()
        host.reading.emit(ReadingState.PAUSED)

        assertEquals(listOf("lock true", "highlight 0..3 0..2", "reveal 0 3"), surface.calls)
        assertEquals(ViewActivity.RUNNING, controls.activity.value)
    }

    @Test
    fun `stop clears the highlight, unlocks and is no finish`() {
        var finished = 0
        controls.onFinished = { finished++ }
        speaking()
        host.reading.position = ReadingPosition(0, 0, 4, null, null)
        host.pollTick?.run()
        surface.calls.clear()

        controls.stop()

        assertEquals(listOf("lock false", "highlight null null"), surface.calls)
        assertEquals(ViewActivity.NONE, controls.activity.value)
        assertEquals(0, finished)
    }

    @Test
    fun `onFinished is called once when the reading reaches its end, not after an error`() {
        var finished = 0
        controls.onFinished = { finished++ }
        speaking()

        host.reading.emit(ReadingState.FINISHED)
        model.setSpeed(1.5)

        assertEquals(1, finished)
        assertFalse(controls.reading)
        assertEquals("lock false", surface.calls.last())

        speaking()
        host.reading.emit(ReadingState.FAILED, "Broken.")
        assertEquals(1, finished)
    }

    @Test
    fun `play from inside onFinished starts the next reading on a locked surface`() {
        controls.onFinished = {
            surface.text = ReadingText("Next chapter.", 0, 13)
            controls.play()
        }
        speaking()

        host.reading.emit(ReadingState.FINISHED)

        assertEquals(2, host.readings.size)
        assertEquals(listOf("Next chapter."), host.reading.chunks.map { it.text })
        assertTrue(controls.reading)
        assertEquals("lock true", surface.calls.last())
        assertEquals(ViewActivity.RUNNING, controls.activity.value)
    }

    @Test
    fun `empty chapters finish at once, so a continuous reading goes on to the next one`() {
        val chapters =
            ArrayDeque(
                listOf(
                    ReadingText("  \n ", 0, 4),
                    ReadingText("[1]", 0, 3, skip = listOf(0..2)),
                    ReadingText("Last one.", 0, 9),
                ),
            )
        var finished = 0
        controls.onFinished = {
            finished++
            // The caller stops after its last chapter.
            chapters.removeFirstOrNull()?.let {
                surface.text = it
                controls.play()
            }
        }
        speaking()

        host.reading.emit(ReadingState.FINISHED)

        assertEquals(3, finished)
        assertEquals(listOf("Last one."), host.reading.chunks.map { it.text })
        assertTrue(controls.reading)
        assertEquals("lock true", surface.calls.last())
    }

    @Test
    fun `jumpTo restarts at the sentence of a shown offset`() {
        speaking()

        controls.jumpTo(11)

        assertEquals(2, host.readings.size)
        assertEquals(2, host.reading.startChunk)
    }

    @Test
    fun `jumpTo before the prepared paragraph reads the last text again from there, and stays paused`() {
        val shown = "First. Second.\n\nThird. Fourth."
        surface.text = ReadingText(shown, shown.indexOf("Fourth"), shown.length)
        speaking()
        assertEquals(listOf("Third.", "Fourth."), host.reading.chunks.map { it.text })
        controls.togglePause()
        host.reading.emit(ReadingState.PAUSED)

        controls.jumpTo(shown.indexOf("Second"))

        assertEquals(2, host.readings.size)
        assertEquals(listOf("First.", "Second.", "Third.", "Fourth."), host.reading.chunks.map { it.text })
        assertEquals(1, host.reading.startChunk)
        assertEquals(listOf("start", "pause"), host.reading.calls)

        controls.jumpTo(shown.length)
        controls.stop()
        controls.jumpTo(0)
        assertEquals(2, host.readings.size, "nothing past the end or while idle")
    }

    @Test
    fun `Shortcut+Enter plays while idle, Space pauses and Escape stops while reading`() {
        assertFalse(key(KeyCode.SPACE))
        assertTrue(key(KeyCode.ENTER, shortcut = true))
        assertEquals(1, host.readings.size)
        host.reading.emit(ReadingState.SPEAKING)

        assertFalse(key(KeyCode.ENTER, shortcut = true))
        assertTrue(key(KeyCode.SPACE))
        assertTrue("pause" in host.reading.calls)
        assertTrue(key(KeyCode.ESCAPE))
        assertFalse(controls.reading)
    }

    @Test
    fun `the notice is visible and managed only while there are setup problems`() {
        assertFalse(controls.notice.isVisible || controls.notice.isManaged)

        host.problems = listOf(MODEL_MISSING)
        controls.play()
        assertTrue(controls.notice.isVisible && controls.notice.isManaged)

        host.problems = emptyList()
        model.checkAgain()
        assertFalse(controls.notice.isVisible || controls.notice.isManaged)
    }

    @Test
    fun `dispose stops the reading, unlocks and detaches from the model`() {
        speaking()

        controls.dispose()

        assertTrue("stop" in host.reading.calls)
        assertEquals("lock false", surface.calls.last())
        assertTrue(fakeControls.disposed)
        val shown = fakeControls.shown.size
        model.playReading(ReadingText("Again.", 0, 6))
        assertEquals(shown, fakeControls.shown.size)
        assertEquals("lock false", surface.calls.last())
    }
}
