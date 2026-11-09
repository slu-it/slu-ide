package dev.sluide.games.minigolf

import javafx.scene.input.KeyCode

/** What a pressed or released key does on the board. */
internal enum class KeyAction { PAUSE, NEW_GAME, CANCEL, CONTINUE }

/**
 * The action of [code], or null for other keys and while a modifier ([hasModifier]: Shortcut, Alt, Ctrl or Meta) is
 * down, so that the menu accelerators keep working. The view passes false for a release so that a release is never
 * lost.
 */
internal fun keyAction(
    code: KeyCode,
    hasModifier: Boolean = false,
): KeyAction? {
    if (hasModifier) return null
    return when (code) {
        KeyCode.P -> KeyAction.PAUSE
        KeyCode.F2 -> KeyAction.NEW_GAME
        KeyCode.ESCAPE -> KeyAction.CANCEL
        KeyCode.SPACE, KeyCode.ENTER -> KeyAction.CONTINUE
        else -> null
    }
}
