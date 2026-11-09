package dev.sluide.games.minigolf.ui

import dev.sluide.games.common.ui.FieldPlacement
import dev.sluide.games.minigolf.Rules
import javafx.geometry.VPos
import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.Color
import javafx.scene.text.TextAlignment

/** The scorecard over the field after a hole and at the end of the round. Texts come from [ScorecardCells]. */
internal object ScorecardPainter {
    private const val LEFT = 12.0
    private const val RIGHT = 308.0
    private const val LABEL_WIDTH = 34.0
    private const val ROW_HEIGHT = 11.0
    private const val BLOCK_GAP = 8.0
    private const val FONT = 7.5
    private val LABELS = listOf("Hole", "Par", "You", "+/−", "Best")

    fun paint(
        gc: GraphicsContext,
        placement: FieldPlacement,
        pars: List<Int>,
        scores: List<Int?>,
        bests: List<Int?>,
        final: Boolean,
        newBest: Boolean,
    ) {
        val cells = ScorecardCells.cells(pars, scores, bests)
        val totals = ScorecardCells.totals(pars, scores)
        val rows = ScorecardCells.rows(cells.size)
        val blockHeight = LABELS.size * ROW_HEIGHT
        val tableHeight = rows.size * blockHeight + (rows.size - 1).coerceAtLeast(0) * BLOCK_GAP
        val top = (Rules.FIELD_HEIGHT - (tableHeight + 56.0)) / 2 + 8
        gc.inField(placement) {
            fill = MiniGolfPalette.CARD_DIM
            fillRect(0.0, 0.0, Rules.FIELD_WIDTH, Rules.FIELD_HEIGHT)
            fill = MiniGolfPalette.CARD_PANEL
            fillRoundRect(LEFT - 6, top - 8, RIGHT - LEFT + 12, tableHeight + 64.0, 8.0, 8.0)
            textBaseline = VPos.CENTER
            font = MiniGolfPalette.font(FONT)
            val cellWidth = (RIGHT - LEFT - LABEL_WIDTH) / ScorecardCells.COLUMNS_PER_ROW
            rows.forEachIndexed { r, range ->
                val y0 = top + r * (blockHeight + BLOCK_GAP)
                block(cells, range, y0, cellWidth)
            }
            val bottom = top + tableHeight
            textAlign = TextAlignment.CENTER
            val summary =
                if (final) {
                    ScorecardCells.finalLine(totals, newBest)
                } else {
                    ScorecardCells.totalLine(totals)
                }
            font = MiniGolfPalette.font(if (final) 10.0 else 9.0)
            fill = if (final) MiniGolfPalette.ACCENT else MiniGolfPalette.CARD_TEXT
            fillText(summary, Rules.FIELD_WIDTH / 2, bottom + 13)
            font = MiniGolfPalette.font(8.0)
            fill = MiniGolfPalette.CARD_LABEL
            fillText(
                if (final) "F2 for a new round" else "Click or press Space to continue",
                Rules.FIELD_WIDTH / 2,
                bottom + 30,
            )
        }
    }

    private fun GraphicsContext.block(
        cells: List<ScorecardCell>,
        range: IntRange,
        y0: Double,
        cellWidth: Double,
    ) {
        LABELS.forEachIndexed { row, label ->
            val cy = y0 + row * ROW_HEIGHT + ROW_HEIGHT / 2
            if (row % 2 == 0) {
                fill = Color.color(1.0, 1.0, 1.0, 0.05)
                fillRect(LEFT, y0 + row * ROW_HEIGHT, RIGHT - LEFT, ROW_HEIGHT)
            }
            textAlign = TextAlignment.LEFT
            fill = MiniGolfPalette.CARD_LABEL
            fillText(label, LEFT + 2, cy)
            textAlign = TextAlignment.CENTER
            range.forEachIndexed { col, i ->
                val cell = cells[i]
                val x = LEFT + LABEL_WIDTH + col * cellWidth + cellWidth / 2
                when (row) {
                    0 -> {
                        fill = MiniGolfPalette.CARD_TEXT
                        fillText(cell.hole, x, cy)
                    }

                    1 -> {
                        fill = MiniGolfPalette.CARD_LABEL
                        fillText(cell.par, x, cy)
                    }

                    2 -> {
                        fill = MiniGolfPalette.resultColor(cell.result)
                        fillText(cell.you, x, cy)
                    }

                    3 -> {
                        fill = MiniGolfPalette.resultColor(cell.result)
                        fillText(cell.toPar, x, cy)
                    }

                    else -> {
                        fill = MiniGolfPalette.CARD_LABEL
                        fillText(cell.best, x, cy)
                    }
                }
            }
        }
        stroke = MiniGolfPalette.CARD_LINE
        lineWidth = 0.6
        strokeLine(LEFT, y0 + ROW_HEIGHT, RIGHT, y0 + ROW_HEIGHT)
    }
}
