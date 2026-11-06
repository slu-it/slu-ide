package dev.sluide.games.sudoku.ui

import dev.sluide.editorarea.EditorTab
import javafx.beans.value.ObservableValue
import javafx.beans.value.ObservableValueBase
import javafx.scene.Node

/** Sudoku shown as an editor tab. */
internal class SudokuTab(
    private val view: SudokuView,
) : EditorTab {
    override val title: ObservableValue<String> =
        object : ObservableValueBase<String>() {
            override fun getValue() = "Sudoku"
        }
    override val tooltip: String = "Fill every row, column and box with each digit once"
    override val focusOnShow: Boolean = true
    override val content: Node get() = view.node

    override fun dispose() = view.dispose()
}
