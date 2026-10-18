package dev.sluide.terminal.view

import com.jediterm.core.input.InputEvent
import javafx.scene.input.KeyCode

private const val ESC: Byte = 27

/** Which modifier keys are held. */
internal data class Modifiers(
    val shift: Boolean = false,
    val control: Boolean = false,
    val alt: Boolean = false,
    val meta: Boolean = false,
) {
    /** The modifiers as AWT extended modifier flags, which JediTerm's key encoder expects. */
    val awtFlags: Int
        get() =
            (if (shift) InputEvent.SHIFT_DOWN_MASK else 0) or
                (if (control) InputEvent.CTRL_DOWN_MASK else 0) or
                (if (alt) InputEvent.ALT_DOWN_MASK else 0) or
                (if (meta) InputEvent.META_DOWN_MASK else 0)
}

/**
 * Translates JavaFX key events into the bytes a terminal sends to the shell.
 *
 * Special keys (arrows, F-keys, Home, …) are encoded by [encode], which is JediTerm's encoder: it knows the modes the
 * running program switched on, such as application cursor keys. Control characters (Enter, Ctrl+C, …) are handled on
 * key press, printable characters on key typed, so nothing is sent twice.
 */
internal class TerminalKeys(
    private val encode: (awtKeyCode: Int, awtModifiers: Int) -> ByteArray?,
    /** Whether Alt+key sends Esc followed by the key, like xterm. Off on macOS, where Option types characters. */
    private val altSendsEscape: Boolean,
) {
    /** The bytes for a pressed key, or `null` if the key is left to [typed] or not meant for the terminal. */
    fun pressed(
        code: KeyCode,
        modifiers: Modifiers,
    ): ByteArray? {
        if (modifiers.meta) return null
        when (code) {
            KeyCode.ENTER -> {
                return byteArrayOf('\r'.code.toByte())
            }

            KeyCode.TAB -> {
                if (!modifiers.shift) return byteArrayOf('\t'.code.toByte())
            }

            KeyCode.ESCAPE -> {
                return byteArrayOf(ESC)
            }

            else -> {}
        }
        // JavaFX key codes equal the AWT virtual key codes for the special keys.
        if (code in SPECIAL_KEYS) return encode(code.code, modifiers.awtFlags)
        // Ctrl+Shift+letter sends nothing, like in most terminals: it is left for shortcuts (copy, paste, New Terminal).
        if (modifiers.control && modifiers.shift && code.isLetterKey) return null
        if (modifiers.control && !modifiers.alt) controlCharacter(code)?.let { return byteArrayOf(it) }
        if (modifiers.alt && !modifiers.control && altSendsEscape && (code.isLetterKey || code.isDigitKey)) {
            val char = code.char.let { if (modifiers.shift) it.uppercase() else it.lowercase() }
            return byteArrayOf(ESC) + char.toByteArray()
        }
        return null
    }

    /** The text for a typed character, or `null` for control characters, which [pressed] handles. */
    fun typed(
        character: String,
        modifiers: Modifiers,
    ): String? {
        if (character.isEmpty() || character.any { it.isISOControl() }) return null
        if (modifiers.control || modifiers.meta) return null
        if (modifiers.alt && altSendsEscape) return null
        return character
    }

    /** Ctrl+letter and the other ASCII control characters, e.g. Ctrl+C = 3, Ctrl+[ = Esc. */
    private fun controlCharacter(code: KeyCode): Byte? =
        when {
            code.isLetterKey -> (code.char[0].uppercaseChar() - 'A' + 1).toByte()
            code == KeyCode.SPACE || code == KeyCode.DIGIT2 -> 0
            code == KeyCode.OPEN_BRACKET -> ESC
            code == KeyCode.BACK_SLASH -> 28
            code == KeyCode.CLOSE_BRACKET -> 29
            code == KeyCode.MINUS -> 31
            else -> null
        }

    private companion object {
        val SPECIAL_KEYS =
            setOf(
                KeyCode.UP,
                KeyCode.DOWN,
                KeyCode.LEFT,
                KeyCode.RIGHT,
                KeyCode.HOME,
                KeyCode.END,
                KeyCode.PAGE_UP,
                KeyCode.PAGE_DOWN,
                KeyCode.INSERT,
                KeyCode.DELETE,
                KeyCode.BACK_SPACE,
                KeyCode.TAB,
                KeyCode.F1,
                KeyCode.F2,
                KeyCode.F3,
                KeyCode.F4,
                KeyCode.F5,
                KeyCode.F6,
                KeyCode.F7,
                KeyCode.F8,
                KeyCode.F9,
                KeyCode.F10,
                KeyCode.F11,
                KeyCode.F12,
            )
    }
}
