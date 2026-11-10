package dev.sluide.games.doubles.ui

import dev.sluide.editorarea.EditorTab
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node

/** Doubles shown as an editor tab. */
internal class DoublesTab(
    private val view: DoublesView,
) : EditorTab {
    override val title: ObservableValue<String> =
        object : ObservableValueBase<String>() {
            override fun getValue() = "Doubles"
        }
    override val tooltip: String = "Slide and merge tiles to reach the goal"
    override val focusOnShow: Boolean = true
    override val content: Node get() = view.node

    override fun icon(): Node? = doublesLogo()

    override fun dispose() = view.dispose()
}
