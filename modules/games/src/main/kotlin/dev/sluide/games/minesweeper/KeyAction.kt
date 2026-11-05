package dev.sluide.games.minesweeper

import javafx.scene.input.KeyCode

/** What a key does on the board. */
internal sealed interface KeyAction {
    data class Move(
        val dx: Int,
        val dy: Int,
    ) : KeyAction

    /** Opens the cursor cell, or chords it when it is opened ([MinesweeperGame.reveal]). */
    data object Reveal : KeyAction

    data object Flag : KeyAction

    data object NewGame : KeyAction

    data object Pause : KeyAction
}

/**
 * The action of [code], or null for other keys and while a modifier ([hasModifier]: Shortcut, Alt or Ctrl) is down, so
 * that the menu accelerators keep working.
 */
internal fun keyAction(
    code: KeyCode,
    hasModifier: Boolean = false,
): KeyAction? {
    if (hasModifier) return null
    return when (code) {
        KeyCode.LEFT -> KeyAction.Move(-1, 0)
        KeyCode.RIGHT -> KeyAction.Move(1, 0)
        KeyCode.UP -> KeyAction.Move(0, -1)
        KeyCode.DOWN -> KeyAction.Move(0, 1)
        KeyCode.SPACE, KeyCode.ENTER -> KeyAction.Reveal
        KeyCode.F -> KeyAction.Flag
        KeyCode.F2 -> KeyAction.NewGame
        KeyCode.P -> KeyAction.Pause
        else -> null
    }
}
