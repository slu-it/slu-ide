package dev.sluide.terminal

import com.jediterm.core.input.MouseWheelEvent
import com.jediterm.terminal.emulator.mouse.MouseButtonCodes
import com.jediterm.terminal.emulator.mouse.MouseButtonModifierFlags
import javafx.scene.input.MouseButton
import com.jediterm.core.input.MouseEvent as TermMouseEvent

/**
 * Translates JavaFX mouse input into JediTerm's mouse events, which its encoder sends to programs that asked for mouse
 * reports (e.g. full-screen programs like Claude Code, `vim` with `mouse=a`, `htop`), in the format they asked for.
 */
internal object TerminalMouse {
    // JediTerm's MouseButtonCodes names these the other way round; xterm's button 4 scrolls up (reported as 64).
    private const val WHEEL_UP = MouseButtonCodes.SCROLLDOWN
    private const val WHEEL_DOWN = MouseButtonCodes.SCROLLUP

    /** The modifiers as xterm mouse report flags. Shift is included, which makes JediTerm not report the event. */
    fun flags(modifiers: Modifiers): Int =
        (if (modifiers.shift) MouseButtonModifierFlags.MOUSE_BUTTON_SHIFT_FLAG else 0) or
            (if (modifiers.alt) MouseButtonModifierFlags.MOUSE_BUTTON_META_FLAG else 0) or
            (if (modifiers.control) MouseButtonModifierFlags.MOUSE_BUTTON_CTRL_FLAG else 0)

    /** The button for mouse reports, or `null` for buttons that are not reported (right opens the context menu). */
    fun button(button: MouseButton): Int? =
        when (button) {
            MouseButton.PRIMARY -> MouseButtonCodes.LEFT
            MouseButton.MIDDLE -> MouseButtonCodes.MIDDLE
            else -> null
        }

    /** The button reported for a drag: the held one, primary first; `null` when only unreported buttons are held. */
    fun heldButton(
        primary: Boolean,
        middle: Boolean,
    ): Int? =
        when {
            primary -> MouseButtonCodes.LEFT
            middle -> MouseButtonCodes.MIDDLE
            else -> null
        }

    fun event(
        type: TermMouseEvent.Type,
        button: Int,
        modifiers: Modifiers,
    ) = TermMouseEvent(type, button, flags(modifiers))

    /**
     * A wheel turn; [lines] is how often it is repeated when sent as arrow keys to a full-screen program that did not
     * ask for mouse reports (mouse reports are sent once per event).
     */
    fun wheel(
        up: Boolean,
        modifiers: Modifiers,
        lines: Int,
    ) = MouseWheelEvent(if (up) WHEEL_UP else WHEEL_DOWN, flags(modifiers), lines)
}
