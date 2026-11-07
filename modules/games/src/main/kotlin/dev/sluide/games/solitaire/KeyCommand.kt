package dev.sluide.games.solitaire

/** What a key on the table asks of the model. */
internal sealed interface KeyCommand {
    /** The cursor's new state. */
    data class Point(
        val cursor: Cursor,
    ) : KeyCommand

    data class Play(
        val move: Move,
    ) : KeyCommand

    /** Drops the picked cards; when illegal the pick stays and the model says "Can't move there". */
    data class Drop(
        val move: Move.Transfer,
    ) : KeyCommand

    /** As a click on the stock. */
    data object Stock : KeyCommand
}

/**
 * What the table key [action] does at [cursor]; null for nothing (and for the keys that are not about the table). D is
 * a click on the stock wherever the cursor is.
 * Without a pile every key puts the cursor on the first column. Space and Enter drop picked cards (on their own pile
 * they are put back) and act as a click on the stock; otherwise Space picks up and Enter plays [MoveChooser.best].
 */
internal fun keyCommand(
    action: KeyAction,
    cursor: Cursor,
    rules: Rules,
    table: Table,
): KeyCommand? {
    val at = cursor.pile
    val picked = cursor.picked
    val pickOrPlay = action == KeyAction.PickOrDrop || action == KeyAction.Play
    return when {
        action is KeyAction.Navigate -> KeyCommand.Point(cursor.moved(action.direction, rules, table))

        action == KeyAction.Stock -> KeyCommand.Stock

        !pickOrPlay && action != KeyAction.Cancel && action != KeyAction.ToFoundation -> null

        // Without a pile the direction does not matter: the cursor goes to the first column.
        at == null -> KeyCommand.Point(cursor.moved(Direction.DOWN, rules, table))

        action == KeyAction.Cancel || (pickOrPlay && picked?.pile == at) -> KeyCommand.Point(cursor.copy(picked = null))

        pickOrPlay && picked != null -> KeyCommand.Drop(Move.Transfer(picked.pile, at, picked.count))

        pickOrPlay && at.kind == PileKind.STOCK -> KeyCommand.Stock

        action == KeyAction.PickOrDrop -> cursor.pickedUp(rules, table)?.let { KeyCommand.Point(it) }

        action == KeyAction.Play -> MoveChooser.best(rules, table, at, cursor.depth)?.let { KeyCommand.Play(it) }

        action == KeyAction.ToFoundation -> MoveChooser.toFoundation(rules, table, at)?.let { KeyCommand.Play(it) }

        else -> null
    }
}
