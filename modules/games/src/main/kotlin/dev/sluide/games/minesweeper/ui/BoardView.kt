package dev.sluide.games.minesweeper.ui

import dev.sluide.games.common.ui.FitArea
import dev.sluide.games.minesweeper.CellKind
import dev.sluide.games.minesweeper.MIN_CELL
import dev.sluide.games.minesweeper.MinesweeperModel
import dev.sluide.games.minesweeper.cellSize
import javafx.geometry.Point2D
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import org.kordamp.ikonli.javafx.FontIcon
import kotlin.math.floor

/**
 * The cells of the board, laid out as squares sized by [cellSize] at the [zoom] and centred. Only shows [model];
 * [update] redraws it, and the view maps mouse clicks to cells with [cellAt].
 *
 * The Fit size is that of the inside of [area], the scroll pane around the board; the board's minimum size is the
 * zoomed board, so that it scrolls above Fit ([FitArea]).
 */
internal class BoardView(
    private val model: MinesweeperModel,
    area: Region,
) : Region() {
    private class CellNode : Label() {
        var shown: List<Any>? = null
    }

    private var cells: List<CellNode> = emptyList()
    private var columns = 0
    private var rows = 0
    private var cell = MIN_CELL
    private var originX = 0.0
    private var originY = 0.0
    private val fitArea = FitArea(area, ::requestLayout)

    /** The factor on the Fit size of the cells (ADR 0155). */
    var zoom = 1.0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    init {
        styleClass += "ms-board"
        isFocusTraversable = true
        focusedProperty().addListener { _, _, _ -> update() }
        update()
    }

    /** The cell under the point (in this node's coordinates), or null outside the board. */
    fun cellAt(
        px: Double,
        py: Double,
    ): Pair<Int, Int>? {
        val x = floor((px - originX) / cell).toInt()
        val y = floor((py - originY) / cell).toInt()
        return if (px >= originX && py >= originY && x in 0 until columns && y in 0 until rows) x to y else null
    }

    /** The middle of the cell, in this node's coordinates. */
    fun cellCenter(
        x: Int,
        y: Int,
    ) = Point2D(originX + (x + 0.5) * cell, originY + (y + 0.5) * cell)

    /** Redraws the cells from the model; a new board size replaces the cells. */
    fun update() {
        val game = model.game
        if (game.size.width != columns || game.size.height != rows) {
            columns = game.size.width
            rows = game.size.height
            cells =
                List(columns * rows) {
                    CellNode().apply {
                        isMouseTransparent = true
                        isFocusTraversable = false
                        alignment = Pos.CENTER
                        minWidth = 0.0
                        minHeight = 0.0
                    }
                }
            children.setAll(cells)
            requestLayout()
        }
        for (y in 0 until rows) {
            for (x in 0 until columns) draw(cells[y * columns + x], x, y)
        }
    }

    private fun draw(
        node: CellNode,
        x: Int,
        y: Int,
    ) {
        val game = model.game
        val kind = game.cell(x, y)
        val number = if (kind == CellKind.OPENED) game.adjacentMines(x, y) else 0
        val cursor = isFocused && x == model.cursorX && y == model.cursorY
        val key = listOf(kind, number, cursor, cell)
        if (node.shown == key) return
        node.shown = key
        val classes = mutableListOf("ms-cell")
        classes +=
            when (kind) {
                CellKind.CLOSED -> "ms-closed"
                CellKind.FLAGGED -> "ms-flag"
                CellKind.OPENED -> "ms-opened"
                CellKind.MINE -> "ms-mine"
                CellKind.HIT_MINE -> "ms-hit"
                CellKind.WRONG_FLAG -> "ms-wrong"
            }
        if (number > 0) classes += "ms-n$number"
        if (cursor) classes += "ms-cursor"
        node.styleClass.setAll(classes)
        node.style = "-fx-font-size: ${(cell * FONT_FACTOR).toInt()}px;"
        node.text = if (number > 0) number.toString() else ""
        node.graphic =
            when (kind) {
                CellKind.FLAGGED -> {
                    icon("mdi2f-flag", "ms-flag-icon")
                }

                CellKind.MINE -> {
                    icon("mdi2b-bomb", "ms-mine-icon")
                }

                CellKind.HIT_MINE -> {
                    icon("mdi2b-bomb", "ms-hit-icon")
                }

                CellKind.WRONG_FLAG -> {
                    StackPane(
                        icon("mdi2f-flag", "ms-flag-icon"),
                        icon("mdi2c-close", "ms-cross-icon"),
                    )
                }

                else -> {
                    null
                }
            }
    }

    private fun icon(
        literal: String,
        styleClass: String,
    ): Node =
        FontIcon(literal).apply {
            iconSize = (cell * ICON_FACTOR).toInt()
            this.styleClass += styleClass
        }

    override fun computeMinWidth(height: Double) = columns * zoomedCell() + 2 * PADDING

    override fun computeMinHeight(width: Double) = rows * zoomedCell() + 2 * PADDING

    override fun computePrefWidth(height: Double) = columns * cell + 2 * PADDING

    override fun computePrefHeight(width: Double) = rows * cell + 2 * PADDING

    override fun layoutChildren() {
        val size = zoomedCell()
        val changed = size != cell
        cell = size
        originX = ((width - columns * cell) / 2).coerceAtLeast(PADDING)
        originY = ((height - rows * cell) / 2).coerceAtLeast(PADDING)
        if (changed) update()
        for (y in 0 until rows) {
            for (x in 0 until columns) {
                cells[y * columns + x].resizeRelocate(originX + x * cell, originY + y * cell, cell, cell)
            }
        }
    }

    /** The cell at the zoom, from the Fit size of the area (at least the zoomed minimum while it is not laid out). */
    private fun zoomedCell() =
        cellSize(fitArea.innerWidth - 2 * PADDING, fitArea.innerHeight - 2 * PADDING, columns, rows, zoom)

    private companion object {
        const val PADDING = 8.0
        const val FONT_FACTOR = 0.55
        const val ICON_FACTOR = 0.6
    }
}
