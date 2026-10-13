package dev.sluide.terminal

import com.jediterm.terminal.emulator.mouse.MouseButtonCodes
import javafx.scene.input.MouseButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerminalMouseTest {
    @Test
    fun `modifiers become xterm mouse flags`() {
        assertEquals(0, TerminalMouse.flags(Modifiers()))
        assertEquals(4, TerminalMouse.flags(Modifiers(shift = true)))
        assertEquals(8, TerminalMouse.flags(Modifiers(alt = true)))
        assertEquals(16, TerminalMouse.flags(Modifiers(control = true)))
        assertEquals(0, TerminalMouse.flags(Modifiers(meta = true)))
    }

    @Test
    fun `left and middle buttons are reported, the right button is not`() {
        assertEquals(MouseButtonCodes.LEFT, TerminalMouse.button(MouseButton.PRIMARY))
        assertEquals(MouseButtonCodes.MIDDLE, TerminalMouse.button(MouseButton.MIDDLE))
        assertNull(TerminalMouse.button(MouseButton.SECONDARY))
        assertEquals(MouseButtonCodes.LEFT, TerminalMouse.heldButton(primary = true, middle = true))
        assertEquals(MouseButtonCodes.MIDDLE, TerminalMouse.heldButton(primary = false, middle = true))
        assertNull(TerminalMouse.heldButton(primary = false, middle = false))
    }

    @Test
    fun `a wheel turn carries the scroll direction and Shift`() {
        // xterm buttons 4 (up) and 5 (down).
        assertEquals(4, TerminalMouse.wheel(up = true, Modifiers(), 3).buttonCode)
        assertEquals(5, TerminalMouse.wheel(up = false, Modifiers(), 3).buttonCode)
        assertEquals(3, TerminalMouse.wheel(up = true, Modifiers(), 3).unitsToScroll)
        assertTrue(TerminalMouse.wheel(up = true, Modifiers(shift = true), 3).isShiftDown())
        assertFalse(TerminalMouse.wheel(up = true, Modifiers(), 3).isShiftDown())
    }
}
