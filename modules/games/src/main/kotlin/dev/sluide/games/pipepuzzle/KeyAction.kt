package dev.sluide.games.pipepuzzle

import javafx.scene.input.KeyCode

/** What a key does on the board. */
internal sealed interface KeyAction {
    data class Move(
        val dRow: Int,
        val dCol: Int,
    ) : KeyAction

    data class Turn(
        val clockwise: Boolean,
    ) : KeyAction

    data object Hint : KeyAction

    data object Restart : KeyAction

    data object Pause : KeyAction

    data object NewGame : KeyAction
}

/**
 * The action of [code]: arrows move, Space and Enter turn clockwise (counter-clockwise with [shift]), H hint, R restart,
 * P pause, F2 new game. [shortcut] (the platform Shortcut key) and [otherModifier] (Alt, or Ctrl/Meta that is not the
 * Shortcut key) give null.
 */
internal fun keyAction(
    code: KeyCode,
    shift: Boolean,
    shortcut: Boolean,
    otherModifier: Boolean,
): KeyAction? {
    if (otherModifier || shortcut) return null
    return when (code) {
        KeyCode.UP -> KeyAction.Move(-1, 0)
        KeyCode.DOWN -> KeyAction.Move(1, 0)
        KeyCode.LEFT -> KeyAction.Move(0, -1)
        KeyCode.RIGHT -> KeyAction.Move(0, 1)
        KeyCode.SPACE, KeyCode.ENTER -> KeyAction.Turn(!shift)
        KeyCode.H -> KeyAction.Hint
        KeyCode.R -> KeyAction.Restart
        KeyCode.P -> KeyAction.Pause
        KeyCode.F2 -> KeyAction.NewGame
        else -> null
    }
}
