package dev.sluide.games.minesweeper.ui

import dev.sluide.editorarea.EditorTab
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node

/** Minesweeper shown as an editor tab. */
internal class MinesweeperTab(
    private val view: MinesweeperView,
) : EditorTab {
    override val title: ObservableValue<String> =
        object : ObservableValueBase<String>() {
            override fun getValue() = "Minesweeper"
        }
    override val tooltip: String = "Play Minesweeper while you wait"
    override val content: Node get() = view.node

    override fun dispose() = view.dispose()
}
