package dev.sluide.games.pipepuzzle.ui

import dev.sluide.editorarea.EditorTab
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node

/** Pipe Puzzle shown as an editor tab. */
internal class PipePuzzleTab(
    private val view: PipePuzzleView,
) : EditorTab {
    override val title: ObservableValue<String> =
        object : ObservableValueBase<String>() {
            override fun getValue() = "Pipe Puzzle"
        }
    override val tooltip: String = "Turn the tiles to connect the flow"
    override val focusOnShow: Boolean = true
    override val content: Node get() = view.node

    override fun icon(): Node? = pipePuzzleLogo()

    override fun dispose() = view.dispose()
}
