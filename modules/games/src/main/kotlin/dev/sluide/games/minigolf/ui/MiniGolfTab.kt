package dev.sluide.games.minigolf.ui

import dev.sluide.editorarea.EditorTab
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node

/** Mini Golf shown as an editor tab. */
internal class MiniGolfTab(
    private val view: MiniGolfView,
) : EditorTab {
    override val title: ObservableValue<String> =
        object : ObservableValueBase<String>() {
            override fun getValue() = "Mini Golf"
        }
    override val tooltip: String = "Putt your way through 18 holes"
    override val focusOnShow: Boolean = true
    override val content: Node get() = view.node

    override fun dispose() = view.dispose()
}
