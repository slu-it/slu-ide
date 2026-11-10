package dev.sluide.games.solitaire.ui

import dev.sluide.editorarea.EditorTab
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node

/** Solitaire shown as an editor tab. */
internal class SolitaireTab(
    private val view: SolitaireView,
) : EditorTab {
    override val title: ObservableValue<String> =
        object : ObservableValueBase<String>() {
            override fun getValue() = "Solitaire"
        }
    override val tooltip: String = "Move every card to the foundations"
    override val focusOnShow: Boolean = true
    override val content: Node get() = view.node

    override fun icon(): Node? = solitaireLogo()

    override fun dispose() = view.dispose()
}
