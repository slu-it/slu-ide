package dev.sluide.games.doubles

import javafx.scene.input.KeyCode

/** What a pressed key does on the board. */
internal sealed interface KeyAction {
    data class Move(
        val direction: Direction,
    ) : KeyAction

    data object Undo : KeyAction

    data object NewGame : KeyAction
}

/**
 * The action of [code], or null for other keys and while a modifier ([hasModifier]: Shortcut, Alt, Ctrl or Meta) is down, so
 * that the menu accelerators keep working.
 */
internal fun keyAction(
    code: KeyCode,
    hasModifier: Boolean = false,
): KeyAction? {
    if (hasModifier) return null
    return when (code) {
        KeyCode.UP, KeyCode.W -> KeyAction.Move(Direction.UP)
        KeyCode.DOWN, KeyCode.S -> KeyAction.Move(Direction.DOWN)
        KeyCode.LEFT, KeyCode.A -> KeyAction.Move(Direction.LEFT)
        KeyCode.RIGHT, KeyCode.D -> KeyAction.Move(Direction.RIGHT)
        KeyCode.U, KeyCode.BACK_SPACE -> KeyAction.Undo
        KeyCode.F2 -> KeyAction.NewGame
        else -> null
    }
}
