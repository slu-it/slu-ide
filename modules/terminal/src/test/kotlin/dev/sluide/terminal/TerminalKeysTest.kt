package dev.sluide.terminal

import com.jediterm.core.input.InputEvent
import javafx.scene.input.KeyCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalKeysTest {
    private val encoded = mutableListOf<Pair<Int, Int>>()
    private val keys =
        TerminalKeys(
            encode = { code, modifiers ->
                encoded += code to modifiers
                "encoded $code".toByteArray()
            },
            altSendsEscape = true,
        )

    private fun pressed(
        code: KeyCode,
        modifiers: Modifiers = Modifiers(),
    ) = keys.pressed(code, modifiers)?.toList()

    private fun bytes(vararg values: Int) = values.map { it.toByte() }

    @Test
    fun `enter, tab and escape send their control characters`() {
        assertEquals(bytes('\r'.code), pressed(KeyCode.ENTER))
        assertEquals(bytes('\t'.code), pressed(KeyCode.TAB))
        assertEquals(bytes(27), pressed(KeyCode.ESCAPE))
    }

    @Test
    fun `ctrl with a letter or bracket sends the control character`() {
        val ctrl = Modifiers(control = true)
        assertEquals(bytes(3), pressed(KeyCode.C, ctrl))
        assertEquals(bytes(4), pressed(KeyCode.D, ctrl))
        assertEquals(bytes(26), pressed(KeyCode.Z, ctrl))
        assertEquals(bytes(27), pressed(KeyCode.OPEN_BRACKET, ctrl))
        assertEquals(bytes(0), pressed(KeyCode.SPACE, ctrl))
    }

    @Test
    fun `ctrl+shift+letter and meta shortcuts are left to the application`() {
        assertNull(pressed(KeyCode.T, Modifiers(control = true, shift = true)))
        assertNull(pressed(KeyCode.C, Modifiers(meta = true)))
    }

    @Test
    fun `special keys are encoded by the emulator with AWT key codes and modifiers`() {
        assertContentEquals("encoded 38".toByteArray(), keys.pressed(KeyCode.UP, Modifiers()))
        keys.pressed(KeyCode.TAB, Modifiers(shift = true))
        keys.pressed(KeyCode.F5, Modifiers())
        keys.pressed(KeyCode.BACK_SPACE, Modifiers())
        keys.pressed(KeyCode.DELETE, Modifiers(control = true))

        assertEquals(
            listOf(38 to 0, 9 to InputEvent.SHIFT_DOWN_MASK, 0x74 to 0, 8 to 0, 0x7F to InputEvent.CTRL_DOWN_MASK),
            encoded,
        )
    }

    @Test
    fun `alt with a letter sends escape and the letter if enabled`() {
        assertEquals(bytes(27, 'f'.code), pressed(KeyCode.F, Modifiers(alt = true)))
        assertEquals(bytes(27, 'B'.code), pressed(KeyCode.B, Modifiers(alt = true, shift = true)))
        assertNull(TerminalKeys({ _, _ -> null }, altSendsEscape = false).pressed(KeyCode.F, Modifiers(alt = true)))
    }

    @Test
    fun `plain letters are not sent on press but when typed`() {
        assertNull(pressed(KeyCode.A))
        assertNull(pressed(KeyCode.A, Modifiers(shift = true)))
        assertEquals("a", keys.typed("a", Modifiers()))
        assertEquals("Ä", keys.typed("Ä", Modifiers(shift = true)))
    }

    @Test
    fun `typed control characters and shortcuts are ignored`() {
        assertNull(keys.typed("\r", Modifiers()))
        assertNull(keys.typed("\u0003", Modifiers(control = true)))
        assertNull(keys.typed("c", Modifiers(control = true)))
        assertNull(keys.typed("c", Modifiers(meta = true)))
        assertNull(keys.typed("f", Modifiers(alt = true)))
        assertNull(keys.typed("", Modifiers()))
    }

    @Test
    fun `option characters are typed on macOS`() {
        val mac = TerminalKeys({ _, _ -> null }, altSendsEscape = false)
        assertEquals("ƒ", mac.typed("ƒ", Modifiers(alt = true)))
    }
}
