package dev.sluide.games.minesweeper.ui

import dev.sluide.games.minesweeper.BoardSize
import javafx.geometry.Insets
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.layout.GridPane
import javafx.stage.Window
import javafx.util.Callback

/** Asks for the width, height and mines of a custom board, starting with [initial]; the result is null on Cancel. */
internal class CustomSizeDialog(
    owner: Window?,
    initial: BoardSize,
) : Dialog<BoardSize>() {
    private val widthSpinner = spinner(BoardSize.MIN_WIDTH, BoardSize.MAX_WIDTH, initial.width)
    private val heightSpinner = spinner(BoardSize.MIN_HEIGHT, BoardSize.MAX_HEIGHT, initial.height)
    private val minesSpinner = spinner(1, BoardSize.maxMines(initial.width, initial.height), initial.mines)

    init {
        title = "Custom Size"
        headerText = null
        if (owner != null) initOwner(owner)
        val start = ButtonType("Start", ButtonData.OK_DONE)
        dialogPane.buttonTypes.setAll(start, ButtonType.CANCEL)
        dialogPane.content =
            GridPane().apply {
                hgap = 10.0
                vgap = 8.0
                padding = Insets(12.0)
                addRow(0, Label("Width"), widthSpinner)
                addRow(1, Label("Height"), heightSpinner)
                addRow(2, Label("Mines"), minesSpinner)
            }
        val limit = { limitMines() }
        widthSpinner.valueProperty().addListener { _, _, _ -> limit() }
        heightSpinner.valueProperty().addListener { _, _, _ -> limit() }
        resultConverter =
            Callback { button ->
                if (button == start) {
                    listOf(widthSpinner, heightSpinner, minesSpinner).forEach(::commit)
                    BoardSize.clamped(widthSpinner.value, heightSpinner.value, minesSpinner.value)
                } else {
                    null
                }
            }
    }

    /** The mines never exceed what the width and height leave room for. */
    private fun limitMines() {
        val max = BoardSize.maxMines(widthSpinner.value, heightSpinner.value)
        val factory = minesSpinner.valueFactory as SpinnerValueFactory.IntegerSpinnerValueFactory
        factory.max = max
        if (factory.value > max) factory.value = max
    }

    private fun spinner(
        min: Int,
        max: Int,
        value: Int,
    ) = Spinner<Int>(min, max, value).apply {
        isEditable = true
        prefWidth = 90.0
        // A typed value counts when the field loses the focus.
        focusedProperty().addListener { _, _, focused -> if (!focused) commit(this) }
    }

    /** Applies the text typed into the editor; an invalid text goes back to the value. */
    private fun commit(spinner: Spinner<Int>) {
        val factory = spinner.valueFactory as SpinnerValueFactory.IntegerSpinnerValueFactory
        val typed =
            spinner.editor.text
                .trim()
                .toIntOrNull()
        if (typed != null) factory.value = typed.coerceIn(factory.min, factory.max)
        spinner.editor.text = factory.value.toString()
    }
}
