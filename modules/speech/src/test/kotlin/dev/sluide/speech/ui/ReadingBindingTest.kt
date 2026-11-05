package dev.sluide.speech.ui

import dev.sluide.fileviewers.StyledRange
import dev.sluide.speech.session.ReadingPosition
import dev.sluide.speech.session.ReadingState
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadingBindingTest {
    private class FakeSurface : ReadingSurface {
        override var caretOffset = 0
        override var selection: IntRange? = null
        override var editable = true
        val overlays = mutableListOf<List<StyledRange>>()
        val reveals = mutableListOf<Pair<Int, Int>>()

        override fun overlay(ranges: List<StyledRange>) {
            overlays += ranges
        }

        override fun reveal(
            start: Int,
            end: Int,
        ) {
            reveals += start to end
        }
    }

    private val host = FakeHost()
    private val model = ReadAloudModel(host)
    private val surface = FakeSurface()
    private var plays = 0
    private val binding = ReadingBinding(model, surface) { plays++ }

    private fun key(
        code: KeyCode,
        shortcut: Boolean = false,
        shift: Boolean = false,
    ) = KeyEvent(
        KeyEvent.KEY_PRESSED,
        "",
        "",
        code,
        shift,
        shortcut && !SHORTCUT_IS_META,
        false,
        shortcut && SHORTCUT_IS_META,
    ).let(binding::onKey)

    private fun speaking() {
        model.read("One. Two. Three.", markdown = false)
        host.reading.emit(ReadingState.SPEAKING)
    }

    @Test
    fun `Shortcut+Enter plays while editable and nothing else does`() {
        assertTrue(key(KeyCode.ENTER, shortcut = true))
        assertEquals(1, plays)
        assertFalse(key(KeyCode.ENTER))
        assertFalse(key(KeyCode.SPACE))
        assertFalse(key(KeyCode.ESCAPE))
        assertEquals(1, plays)
    }

    @Test
    fun `Space pauses and Escape stops while locked`() {
        speaking()

        assertTrue(key(KeyCode.SPACE))
        assertEquals(listOf("start", "pause"), host.reading.calls)
        assertTrue(key(KeyCode.ESCAPE))
        assertTrue("stop" in host.reading.calls)
    }

    @Test
    fun `while locked keys with a modifier and Shortcut+Enter are not handled`() {
        speaking()

        assertFalse(key(KeyCode.SPACE, shift = true))
        assertFalse(key(KeyCode.ESCAPE, shortcut = true))
        assertFalse(key(KeyCode.ENTER, shortcut = true))
        assertEquals(0, plays)
        assertEquals(listOf("start"), host.reading.calls)
    }

    @Test
    fun `apply sends editable, and overlay and reveal only when they changed`() {
        speaking()
        host.reading.position =
            ReadingPosition(1, 5, 9, 5, 8)
        host.pollTick?.run()

        binding.apply(model.ui)
        binding.apply(model.ui)

        assertFalse(surface.editable)
        assertEquals(1, surface.overlays.size)
        assertEquals(listOf(5 to 8), surface.reveals)

        binding.resetApplied()
        binding.apply(model.ui)
        assertEquals(2, surface.overlays.size)
        assertEquals(2, surface.reveals.size)
    }

    @ParameterizedTest
    @EnumSource(value = ReadingState::class, names = ["SPEAKING", "PAUSED"])
    fun `a double-click jumps to the selection start while locked only`(state: ReadingState) {
        binding.onDoubleClick()
        assertTrue(host.readings.isEmpty())

        speaking()
        host.reading.emit(state)
        surface.selection = 11 until 16
        binding.onDoubleClick()

        assertEquals(2, host.readings.size)
        assertEquals(2, host.reading.startChunk)
    }

    @Test
    fun `a double-click without a selection jumps to the caret`() {
        speaking()
        surface.caretOffset = 6

        binding.onDoubleClick()

        assertEquals(1, host.reading.startChunk)
        assertEquals(2, host.readings.size)
    }
}

private val SHORTCUT_IS_META = System.getProperty("os.name").startsWith("Mac")
