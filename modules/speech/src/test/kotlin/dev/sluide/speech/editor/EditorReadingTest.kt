package dev.sluide.speech.editor

import dev.sluide.fileviewers.ViewActivity
import dev.sluide.speech.session.ReadingPosition
import dev.sluide.speech.session.ReadingState
import dev.sluide.speech.ui.FakeHost
import dev.sluide.speech.ui.MODEL_MISSING
import dev.sluide.speech.ui.ReadAloudModel
import javafx.scene.control.IndexRange
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorReadingTest {
    private val host = FakeHost()
    private val model = ReadAloudModel(host, ownsText = false)
    private val controls = FakeControls()

    private fun reading(editor: FakeCodeEditor) = EditorReading(model, editor, { controls }, "speech.css")

    private fun txt(text: String = "One. Two. Three.") = FakeCodeEditor("/p/a.txt", text.split("\n"), plainText = true)

    private fun enter() = KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, !META, false, META)

    @Test
    fun `it adds the controls, the stylesheet, the filters, the text listener and the activity`() {
        val editor = txt()
        reading(editor)

        assertEquals(
            setOf("toolbar", "stylesheet", "filter KEY_PRESSED", "filter MOUSE_CLICKED", "text", "activity"),
            editor.open,
        )
    }

    @Test
    fun `the editor is locked while reading, the highlight goes to the layer, and stop releases it`() {
        val editor = txt()
        val reading = reading(editor)

        reading.play()
        host.reading.emit(ReadingState.SPEAKING)
        host.reading.position = ReadingPosition(0, 0, 4, null, null)
        host.pollTick?.run()

        assertEquals(1, editor.locks)
        assertEquals(listOf(0 to 4), editor.overlays.getValue(READ_ALOUD_LAYER).map { it.start to it.end })
        assertEquals(ViewActivity.RUNNING, editor.activities.single().value)

        model.stop()

        assertEquals(0, editor.locks)
        assertEquals(emptyList(), editor.overlays.getValue(READ_ALOUD_LAYER))
        assertEquals(ViewActivity.NONE, editor.activities.single().value)
    }

    @Test
    fun `play reads from the caret's sentence of the current text`() {
        val editor = txt("One.\nTwo. Three.")
        editor.caret = 7
        reading(editor).play()

        assertEquals(1, host.reading.startChunk)
    }

    @Test
    fun `play reads only the selection`() {
        val editor = txt()
        editor.selected = IndexRange(5, 9)
        reading(editor).play()

        assertEquals(listOf("Two."), host.reading.chunks.map { it.text })
    }

    @Test
    fun `a Markdown file is prepared as Markdown by its name at each play`() {
        val editor = FakeCodeEditor("/p/a.txt", listOf("# Hi there."), plainText = true)
        val reading = reading(editor)
        reading.play()
        assertEquals("# Hi there.", host.reading.chunks.joinToString(" ") { it.text })
        model.stop()

        editor.fileProperty.set(Path.of("/p/a.md"))
        reading.play()
        assertEquals("Hi there.", host.reading.chunks.joinToString(" ") { it.text })
    }

    @Test
    fun `a text over 1 MiB is refused with a status`() {
        val editor = txt("a".repeat(1024 * 1024 + 1))
        reading(editor).play()

        assertTrue(host.readings.isEmpty())
        assertEquals("The text is larger than 1 MiB.", model.ui.status)
        assertEquals(0, editor.locks)
    }

    @Test
    fun `the 1 MiB limit measures the selection when there is one`() {
        val editor = txt("One. " + "a".repeat(1024 * 1024))
        editor.selected = IndexRange(0, 4)
        val reading = reading(editor)

        reading.play()
        assertEquals(1, host.readings.size)
        model.stop()

        editor.selected = IndexRange(0, 1024 * 1024 + 1)
        reading.play()
        assertEquals(1, host.readings.size)
        assertEquals("The text is larger than 1 MiB.", model.ui.status)
    }

    @Test
    fun `a text change stops a reading, and does nothing otherwise`() {
        val editor = txt()
        reading(editor).play()
        host.reading.emit(ReadingState.SPEAKING)

        editor.textChanged()

        assertFalse(model.reading)
        assertEquals(0, editor.locks)
        editor.textChanged()
        assertEquals(1, host.readings.size)
    }

    @Test
    fun `Shortcut+Enter plays, and Escape and Space act while reading, double-click jumps`() {
        val editor = txt()
        reading(editor)
        val play = enter()

        editor.fire(play)
        assertTrue(play.isConsumed)
        assertEquals(1, host.readings.size)
        host.reading.emit(ReadingState.SPEAKING)

        val space = KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SPACE, false, false, false, false)
        editor.fire(space)
        assertTrue(space.isConsumed)
        assertTrue("pause" in host.reading.calls)

        editor.selected = IndexRange(5, 8)
        editor.fire(click(2))
        assertEquals(2, host.readings.size)

        val escape = KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false)
        editor.fire(escape)
        assertTrue(escape.isConsumed)
        assertFalse(model.reading)
    }

    @Test
    fun `a single click does not jump`() {
        val editor = txt()
        reading(editor).play()
        host.reading.emit(ReadingState.SPEAKING)

        editor.fire(click(1))

        assertEquals(1, host.readings.size)
    }

    @Test
    fun `the setup notice is a banner only while there are problems`() {
        host.problems = listOf(MODEL_MISSING)
        val editor = txt()
        reading(editor).play()
        assertTrue("banner" in editor.open)

        host.problems = emptyList()
        model.checkAgain()
        assertFalse("banner" in editor.open)
    }

    @Test
    fun `close stops the reading and closes everything`() {
        val editor = txt()
        val reading = reading(editor)
        reading.play()
        host.reading.emit(ReadingState.SPEAKING)

        reading.close()

        assertEquals(emptySet(), editor.open)
        assertEquals(0, editor.locks)
        assertFalse(model.reading)
        assertTrue(controls.disposed)
        assertTrue(host.reading.calls.contains("stop"))
    }

    private fun click(count: Int) =
        MouseEvent(
            MouseEvent.MOUSE_CLICKED,
            0.0,
            0.0,
            0.0,
            0.0,
            MouseButton.PRIMARY,
            count,
            false,
            false,
            false,
            false,
            true,
            false,
            false,
            false,
            false,
            false,
            null,
        )
}

private val META = System.getProperty("os.name").startsWith("Mac")
