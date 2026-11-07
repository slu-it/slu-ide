package dev.sluide.games.blockbreaker

import javafx.scene.input.KeyCode

/** What a pressed or released key does on the board. */
internal enum class KeyAction { LEFT, RIGHT, LAUNCH, PAUSE, NEW_GAME }

/**
 * The action of [code], or null for other keys and while a modifier ([hasModifier]: Shortcut, Alt, Ctrl or Meta) is down, so
 * that the menu accelerators keep working. The view passes false for a release so that a release is never lost.
 */
internal fun keyAction(
    code: KeyCode,
    hasModifier: Boolean = false,
): KeyAction? {
    if (hasModifier) return null
    return when (code) {
        KeyCode.LEFT, KeyCode.A -> KeyAction.LEFT
        KeyCode.RIGHT, KeyCode.D -> KeyAction.RIGHT
        KeyCode.SPACE, KeyCode.UP, KeyCode.W -> KeyAction.LAUNCH
        KeyCode.P -> KeyAction.PAUSE
        KeyCode.F2 -> KeyAction.NEW_GAME
        else -> null
    }
}
