package dev.sluide.games.solitaire.ui

import dev.sluide.games.solitaire.Move
import dev.sluide.games.solitaire.Rules
import dev.sluide.games.solitaire.Table

/** What the [TableView] needs from the game; the view never changes the game itself. */
internal interface TableHost {
    val rules: Rules
    val table: Table

    /** Applies [move] if legal (the model records it); false if not. */
    fun play(move: Move): Boolean

    /** A click on the stock: Klondike draws or recycles, Spider deals a row; the host decides and reports. */
    fun stockClicked()
}
