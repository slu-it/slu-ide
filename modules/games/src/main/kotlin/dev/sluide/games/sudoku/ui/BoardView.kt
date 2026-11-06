package dev.sluide.games.sudoku.ui

import dev.sluide.games.sudoku.Cell
import dev.sluide.games.sudoku.Flash
import dev.sluide.games.sudoku.SudokuGame
import dev.sluide.games.sudoku.SudokuSize
import javafx.animation.FadeTransition
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.GridPane
import javafx.scene.layout.Region
import javafx.scene.layout.RowConstraints
import javafx.scene.layout.StackPane
import javafx.util.Duration

/**
 * The Sudoku board: a grid of cell nodes, each holding the digit or the notes grid and a flash overlay. It only
 * shows what [show] is given; clicks go to [onCellPressed].
 */
internal class BoardView : GridPane() {
    /** A tint fade lasts this long; the solved sweep delays each cell by its row and column. Zero shows no flash. */
    var flashDuration: Duration = Duration.millis(FLASH_MILLIS)

    /** Called when a cell is pressed. */
    var onCellPressed: (Cell) -> Unit = {}

    private class CellNode(
        val cell: Cell,
        val root: StackPane,
        val digit: Label,
        val notes: GridPane,
        val noteLabels: List<Label>,
        val flash: Region,
    )

    private var size: SudokuSize? = null
    private var cellSize = 0.0
    private var nodes: List<CellNode> = emptyList()
    private val fades = mutableMapOf<Region, FadeTransition>()

    init {
        styleClass += "sdk-board"
        isFocusTraversable = true
        setMaxSize(USE_PREF_SIZE, USE_PREF_SIZE)
    }

    /** Shows [game] (null: an empty grid of [size]) with [selected] highlighted and cells of side [cell]. */
    fun show(
        game: SudokuGame?,
        size: SudokuSize,
        selected: Cell?,
        cell: Double,
    ) {
        if (size != this.size || cell != cellSize) rebuild(size, cell)
        val selectedDigit = if (game != null && selected != null) game.digit(selected) else 0
        val peers = if (selected != null) size.peers(selected) else emptySet()
        val conflicts = game?.conflicts ?: emptySet()
        for (node in nodes) {
            val c = node.cell
            val digit = game?.digit(c) ?: 0
            val given = game?.isGiven(c) == true
            val conflict = c in conflicts
            node.digit.text = if (digit != 0) digit.toString() else ""
            node.digit.isVisible = digit != 0
            node.digit.setClass("sdk-digit-given", given)
            node.digit.setClass("sdk-digit-entry", digit != 0 && !given)
            node.digit.setClass("sdk-digit-conflict", conflict && !given)
            val notes = if (digit == 0 && game != null) game.notes(c) else emptySet()
            node.notes.isVisible = notes.isNotEmpty()
            node.noteLabels.forEachIndexed { i, label ->
                val d = i + 1
                label.text = if (d in notes) d.toString() else ""
                label.setClass("sdk-note-same", d in notes && d == selectedDigit)
            }
            node.root.setClass("sdk-given", given)
            node.root.setClass("sdk-entry", digit != 0 && !given)
            node.root.setClass("sdk-conflict", conflict)
            node.root.setClass("sdk-selected", c == selected)
            node.root.setClass("sdk-peer", c in peers)
            node.root.setClass("sdk-same", selectedDigit != 0 && digit == selectedDigit && c != selected)
        }
    }

    /** Plays [flash]; nothing when [flashDuration] is zero. */
    fun flash(flash: Flash) {
        if (flashDuration <= Duration.ZERO) return
        when (flash) {
            is Flash.Units -> {
                flash.units
                    .flatten()
                    .distinct()
                    .forEach { fade(it, "sdk-flash", Duration.ZERO) }
            }

            Flash.Solved -> {
                val step = flashDuration.multiply(SWEEP_STEP)
                nodes.forEach { fade(it.cell, "sdk-flash", step.multiply((it.cell.row + it.cell.col).toDouble())) }
            }

            is Flash.Wrong -> {
                fade(flash.cell, "sdk-flash-wrong", Duration.ZERO)
            }
        }
    }

    /** Stops the running flashes. */
    fun stopFlashes() {
        fades.values.forEach { it.stop() }
        fades.clear()
        nodes.forEach { it.flash.opacity = 0.0 }
    }

    private fun fade(
        cell: Cell,
        styleClass: String,
        delay: Duration,
    ) {
        val side = size?.side ?: return
        val overlay = nodes.getOrNull(cell.row * side + cell.col)?.flash ?: return
        overlay.styleClass.removeAll("sdk-flash", "sdk-flash-wrong")
        overlay.styleClass += styleClass
        fades.remove(overlay)?.stop()
        overlay.opacity = 0.0
        val fade =
            FadeTransition(flashDuration, overlay).apply {
                fromValue = FLASH_OPACITY
                toValue = 0.0
                this.delay = delay
                setOnFinished { fades.remove(overlay) }
            }
        fades[overlay] = fade
        fade.play()
    }

    /** The overlay's opacity of [cell]; a flash is running while it is above zero. */
    internal fun flashOpacity(cell: Cell): Double = nodes[cell.row * checkNotNull(size).side + cell.col].flash.opacity

    internal fun cellRoot(cell: Cell): Node = nodes[cell.row * checkNotNull(size).side + cell.col].root

    /** The digit shown in [cell], empty for none. */
    internal fun digitText(cell: Cell): String = nodes[cell.row * checkNotNull(size).side + cell.col].digit.text

    /** The notes shown in [cell]. */
    internal fun noteDigits(cell: Cell): Set<Int> =
        nodes[cell.row * checkNotNull(size).side + cell.col]
            .noteLabels
            .mapIndexedNotNull { i, label -> (i + 1).takeIf { label.text.isNotEmpty() } }
            .toSet()

    private fun rebuild(
        size: SudokuSize,
        cell: Double,
    ) {
        stopFlashes()
        this.size = size
        cellSize = cell
        children.clear()
        val digitStyle = "-fx-font-size: ${cell * DIGIT_FACTOR}px"
        val noteStyle = "-fx-font-size: ${cell * NOTE_FACTOR}px"
        nodes =
            size.cells.map { c ->
                val labels =
                    (1..size.side).map { d ->
                        Label().apply {
                            styleClass += "sdk-note"
                            style = noteStyle
                            alignment = Pos.CENTER
                            setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE)
                        }
                    }
                val notes =
                    GridPane().apply {
                        styleClass += "sdk-notes"
                        isMouseTransparent = true
                        isVisible = false
                        alignment = Pos.CENTER
                        labels.forEachIndexed { i, label ->
                            add(label, i % size.boxCols, i / size.boxCols)
                        }
                        for (r in 0 until size.boxRows) {
                            rowConstraints +=
                                RowConstraints().apply {
                                    percentHeight = PERCENT / size.boxRows
                                }
                        }
                        for (col in 0 until size.boxCols) {
                            columnConstraints +=
                                ColumnConstraints().apply {
                                    percentWidth = PERCENT / size.boxCols
                                }
                        }
                    }
                val digit =
                    Label().apply {
                        styleClass += "sdk-digit"
                        style = digitStyle
                        isMouseTransparent = true
                    }
                val flash =
                    Region().apply {
                        isMouseTransparent = true
                        opacity = 0.0
                    }
                val root =
                    StackPane(notes, digit, flash).apply {
                        styleClass += "sdk-cell"
                        if ((c.col + 1) % size.boxCols == 0 && c.col < size.side - 1) styleClass += "sdk-box-right"
                        if ((c.row + 1) % size.boxRows == 0 && c.row < size.side - 1) styleClass += "sdk-box-bottom"
                        setMinSize(cell, cell)
                        setPrefSize(cell, cell)
                        setMaxSize(cell, cell)
                        setOnMousePressed {
                            onCellPressed(c)
                            it.consume()
                        }
                    }
                add(root, c.col, c.row)
                CellNode(c, root, digit, notes, labels, flash)
            }
    }

    private fun Node.setClass(
        name: String,
        on: Boolean,
    ) {
        if (on) {
            if (name !in styleClass) styleClass += name
        } else {
            styleClass -= name
        }
    }

    private companion object {
        const val FLASH_MILLIS = 400.0
        const val FLASH_OPACITY = 0.7
        const val SWEEP_STEP = 0.1
        const val DIGIT_FACTOR = 0.55
        const val NOTE_FACTOR = 0.22
        const val PERCENT = 100.0
    }
}
