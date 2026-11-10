package dev.sluide.games.minesweeper.ui

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.shape.Line
import javafx.scene.shape.Rectangle
import javafx.scene.shape.StrokeLineCap

private val MINE = Color.web("#d6504a")
private val CORE = Color.web("#7a1f1c")

/** The Minesweeper logo: a round mine with eight spikes and a dark core, in a 16 by 16 box (a fresh node per call). */
internal fun minesweeperLogo(): Node {
    val spikes =
        listOf(0.0 to 1.0, 1.0 to 0.0, 0.7 to 0.7, 0.7 to -0.7).map { (dx, dy) ->
            Line(8 - dx * 7, 8 - dy * 7, 8 + dx * 7, 8 + dy * 7).apply {
                stroke = MINE
                strokeWidth = 1.6
                strokeLineCap = StrokeLineCap.ROUND
            }
        }
    return Group(
        Rectangle(16.0, 16.0, Color.TRANSPARENT),
        *spikes.toTypedArray(),
        Circle(8.0, 8.0, 4.6, MINE),
        Circle(8.0, 8.0, 1.7, CORE),
    )
}
