package dev.sluide.games.sudoku.ui

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.paint.Color
import javafx.scene.shape.Line
import javafx.scene.shape.Rectangle

private val GRID = Color.web("#4f86e0")
private val CELL = Color.web("#f0a53a")

/** The Sudoku logo: a three by three grid with one highlighted cell, in a 16 by 16 box (a fresh node per call). */
internal fun sudokuLogo(): Node {
    val lines =
        listOf(5.83, 10.17)
            .flatMap { at -> listOf(Line(at, 1.5, at, 14.5), Line(1.5, at, 14.5, at)) }
            .onEach {
                it.stroke = GRID
                it.strokeWidth = 1.0
            }
    val cell = Rectangle(5.83, 5.83, 4.34, 4.34).apply { fill = CELL }
    val frame =
        Rectangle(1.5, 1.5, 13.0, 13.0).apply {
            fill = Color.TRANSPARENT
            stroke = GRID
            strokeWidth = 1.6
        }
    return Group(Rectangle(16.0, 16.0, Color.TRANSPARENT), cell, *lines.toTypedArray(), frame)
}
