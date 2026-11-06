package dev.sluide.games.sudoku

import javafx.scene.input.KeyCode

/** What a key does on the board. */
internal sealed interface KeyAction {
    data class Move(
        val dRow: Int,
        val dCol: Int,
    ) : KeyAction

    data class Digit(
        val digit: Int,
    ) : KeyAction

    data class Note(
        val digit: Int,
    ) : KeyAction

    data object Erase : KeyAction

    data object ToggleNotes : KeyAction

    data object Hint : KeyAction

    data object Pause : KeyAction

    data object NewGame : KeyAction

    data object Undo : KeyAction

    data object Redo : KeyAction
}

/**
 * The action of [code]. [shortcut] is the platform Shortcut key: with it only Z (undo) and Shift+Z (redo) count.
 * [otherModifier] (Alt, or Ctrl/Meta that is not the Shortcut key) gives null. Shift+digit is a note, read from the
 * key code because Shift+1 types "!". Digits above the grid size are the model's to ignore.
 */
internal fun keyAction(
    code: KeyCode,
    shift: Boolean,
    shortcut: Boolean,
    otherModifier: Boolean,
): KeyAction? {
    if (otherModifier) return null
    if (shortcut) {
        return if (code == KeyCode.Z) {
            if (shift) {
                KeyAction.Redo
            } else {
                KeyAction.Undo
            }
        } else {
            null
        }
    }
    digitOf(code)?.let { return if (shift) KeyAction.Note(it) else KeyAction.Digit(it) }
    return when (code) {
        KeyCode.DIGIT0, KeyCode.NUMPAD0, KeyCode.BACK_SPACE, KeyCode.DELETE -> KeyAction.Erase
        KeyCode.UP, KeyCode.W -> KeyAction.Move(-1, 0)
        KeyCode.DOWN, KeyCode.S -> KeyAction.Move(1, 0)
        KeyCode.LEFT, KeyCode.A -> KeyAction.Move(0, -1)
        KeyCode.RIGHT, KeyCode.D -> KeyAction.Move(0, 1)
        KeyCode.N -> KeyAction.ToggleNotes
        KeyCode.H -> KeyAction.Hint
        KeyCode.P -> KeyAction.Pause
        KeyCode.F2 -> KeyAction.NewGame
        KeyCode.U -> if (shift) KeyAction.Redo else KeyAction.Undo
        else -> null
    }
}

private fun digitOf(code: KeyCode): Int? {
    val name = code.name
    if (!name.startsWith("DIGIT") && !name.startsWith("NUMPAD")) return null
    return name.last().digitToIntOrNull()?.takeIf { it in 1..9 && name.length in 6..7 }
}
