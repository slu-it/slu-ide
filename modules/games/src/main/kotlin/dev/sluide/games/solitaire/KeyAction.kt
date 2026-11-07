package dev.sluide.games.solitaire

import javafx.scene.input.KeyCode

/** What a key does on the Solitaire table. */
internal sealed interface KeyAction {
    /** Moves the cursor; Up and Down in a column also select a deeper or shallower run. */
    data class Navigate(
        val direction: Direction,
    ) : KeyAction

    /** Space: picks up the cursor's cards or drops the picked ones on the cursor's pile; on the stock it draws. */
    data object PickOrDrop : KeyAction

    /** Enter: drops the picked cards, else the smart move of the cursor's cards; on the stock it draws. */
    data object Play : KeyAction

    /** D: as a click on the stock, wherever the cursor is. */
    data object Stock : KeyAction

    /** Escape: puts the picked cards back. */
    data object Cancel : KeyAction

    /** The cursor's top card to a foundation. */
    data object ToFoundation : KeyAction

    data object NewGame : KeyAction

    data object Undo : KeyAction

    data object Redo : KeyAction

    data object Hint : KeyAction

    data object Pause : KeyAction
}

/**
 * The action of [code]. Only the arrow keys move the cursor (D draws, so W A S D is no cursor block here). [shortcut] is the platform Shortcut key: with it only Z (undo) and Shift+Z (redo) count.
 * [otherModifier] (Alt, or Ctrl/Meta that is not the Shortcut key) gives null.
 */
internal fun keyAction(
    code: KeyCode,
    shift: Boolean,
    shortcut: Boolean,
    otherModifier: Boolean,
): KeyAction? {
    if (otherModifier) return null
    if (shortcut) {
        return when {
            code != KeyCode.Z -> null
            shift -> KeyAction.Redo
            else -> KeyAction.Undo
        }
    }
    return when (code) {
        KeyCode.UP -> KeyAction.Navigate(Direction.UP)
        KeyCode.DOWN -> KeyAction.Navigate(Direction.DOWN)
        KeyCode.LEFT -> KeyAction.Navigate(Direction.LEFT)
        KeyCode.RIGHT -> KeyAction.Navigate(Direction.RIGHT)
        KeyCode.D -> KeyAction.Stock
        KeyCode.SPACE -> KeyAction.PickOrDrop
        KeyCode.ENTER -> KeyAction.Play
        KeyCode.ESCAPE -> KeyAction.Cancel
        KeyCode.F -> KeyAction.ToFoundation
        KeyCode.F2 -> KeyAction.NewGame
        KeyCode.U -> if (shift) KeyAction.Redo else KeyAction.Undo
        KeyCode.H -> KeyAction.Hint
        KeyCode.P -> KeyAction.Pause
        else -> null
    }
}
