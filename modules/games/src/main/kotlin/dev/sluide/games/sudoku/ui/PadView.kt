package dev.sluide.games.sudoku.ui

import dev.sluide.games.sudoku.PAD_COLUMNS
import dev.sluide.games.sudoku.SudokuGame
import dev.sluide.games.sudoku.SudokuSize
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ContentDisplay
import javafx.scene.control.Label
import javafx.scene.control.ToggleButton
import javafx.scene.control.Tooltip
import javafx.scene.layout.GridPane
import javafx.scene.layout.VBox
import org.kordamp.ikonli.javafx.FontIcon

/**
 * The number pad: the digits 1..side (each with how many are still missing), Erase and the Notes toggle, in three
 * columns of cell-sized buttons. It only shows what [show] is given; presses go to the callbacks.
 */
internal class PadView : GridPane() {
    var onDigit: (Int) -> Unit = {}
    var onErase: () -> Unit = {}
    var onNotes: () -> Unit = {}

    private var size: SudokuSize? = null
    private var cellSize = 0.0
    private var digits: List<DigitButton> = emptyList()
    private val erase =
        Button().apply {
            styleClass += "sdk-pad-button"
            graphic = FontIcon("mdi2e-eraser").apply { iconSize = ICON_SIZE }
            tooltip = Tooltip("Erase (0, Backspace)")
            setOnAction { onErase() }
        }
    internal val notes =
        ToggleButton().apply {
            styleClass += "sdk-pad-button"
            graphic = FontIcon("mdi2p-pencil-outline").apply { iconSize = ICON_SIZE }
            tooltip = Tooltip("Notes (N)")
            setOnAction { onNotes() }
        }

    internal class DigitButton(
        val digit: Int,
        val button: Button,
        val remaining: Label,
    )

    init {
        styleClass += "sdk-pad"
        listOf(erase, notes).forEach {
            it.isFocusTraversable = false
            it.contentDisplay = ContentDisplay.GRAPHIC_ONLY
        }
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE)
    }

    /** Shows the counts of [game] (null: none), the notes mode and sizes the buttons to [cell]. */
    fun show(
        game: SudokuGame?,
        size: SudokuSize,
        notesMode: Boolean,
        cell: Double,
    ) {
        if (size != this.size || cell != cellSize) rebuild(size, cell)
        for (d in digits) {
            val left = game?.remaining(d.digit) ?: size.side
            d.remaining.text = left.toString()
            d.button.styleClass -= "sdk-pad-done"
            if (game != null && left == 0) d.button.styleClass += "sdk-pad-done"
        }
        notes.isSelected = notesMode
        if (notesMode) {
            if ("sdk-pad-notes" !in styleClass) styleClass += "sdk-pad-notes"
        } else {
            styleClass -= "sdk-pad-notes"
        }
    }

    internal fun digitButton(digit: Int): Button = digits.first { it.digit == digit }.button

    internal fun eraseButton(): Button = erase

    internal fun remainingText(digit: Int): String = digits.first { it.digit == digit }.remaining.text

    private fun rebuild(
        size: SudokuSize,
        cell: Double,
    ) {
        this.size = size
        cellSize = cell
        children.clear()
        val inner = cell - 2 * MARGIN
        digits =
            (1..size.side).map { d ->
                val digitLabel =
                    Label(d.toString()).apply {
                        styleClass += "sdk-pad-digit"
                        style = "-fx-font-size: ${cell * DIGIT_FACTOR}px"
                    }
                val remaining =
                    Label().apply {
                        styleClass += "sdk-pad-remaining"
                        style = "-fx-font-size: ${cell * REMAINING_FACTOR}px"
                    }
                val button =
                    Button().apply {
                        styleClass += "sdk-pad-button"
                        isFocusTraversable = false
                        graphic = VBox(digitLabel, remaining).apply { alignment = Pos.CENTER }
                        contentDisplay = ContentDisplay.GRAPHIC_ONLY
                        setOnAction { onDigit(d) }
                    }
                DigitButton(d, button, remaining)
            }
        val buttons = digits.map { it.button } + erase + notes
        val lastRow = (size.side + PAD_COLUMNS - 1) / PAD_COLUMNS
        buttons.forEachIndexed { i, button ->
            val index = if (i < size.side) i else (lastRow * PAD_COLUMNS) + (i - size.side)
            button.setMinSize(inner, inner)
            button.setPrefSize(inner, inner)
            button.setMaxSize(inner, inner)
            setMargin(button, Insets(MARGIN))
            add(button, index % PAD_COLUMNS, index / PAD_COLUMNS)
        }
    }

    private companion object {
        const val ICON_SIZE = 18
        const val MARGIN = 2.0
        const val DIGIT_FACTOR = 0.45
        const val REMAINING_FACTOR = 0.2
    }
}
