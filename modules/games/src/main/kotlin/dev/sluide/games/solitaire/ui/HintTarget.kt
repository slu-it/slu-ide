package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.Mode
import dev.sluide.games.solitaire.Move
import dev.sluide.games.solitaire.PileId
import dev.sluide.games.solitaire.PileKind
import dev.sluide.games.solitaire.Variant

/** What the table outlines for a hint: the top [count] cards of [from] and the pile [to]. */
internal data class HintTarget(
    val from: PileId,
    val count: Int,
    val to: PileId,
)

/**
 * The outline of [move] on a table of [mode]: a transfer as it is; the stock moves (draw, recycle, deal a row) show
 * the stock and where the card goes: the waste in Klondike, the stock itself in Spider.
 */
internal fun hintTarget(
    move: Move,
    mode: Mode,
): HintTarget {
    val stock = PileId(PileKind.STOCK)
    return when (move) {
        is Move.Transfer -> {
            HintTarget(move.from, move.count, move.to)
        }

        Move.Draw, Move.Recycle, Move.DealRow -> {
            HintTarget(stock, 1, if (mode.variant == Variant.KLONDIKE) PileId(PileKind.WASTE) else stock)
        }
    }
}
