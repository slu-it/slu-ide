package dev.sluide.games.doubles.ui

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.shape.SVGPath
import javafx.scene.shape.StrokeLineCap
import javafx.scene.shape.StrokeLineJoin

private val LOW = Color.web("#c9692a")
private val HIGH = Color.web("#f2c14e")
private val EDGE = Color.web("#6b4311")

/**
 * The Doubles logo: two overlapping tiles with an arrow merging into the upper one,
 * in a 16 by 16 box (a fresh node per call).
 */
internal fun doublesLogo(): Node {
    fun tile(
        x: Double,
        y: Double,
        color: Color,
    ) = Rectangle(x, y, 8.0, 8.0).apply {
        fill = color
        stroke = EDGE
        strokeWidth = 0.8
        arcWidth = 3.0
        arcHeight = 3.0
    }
    val arrow =
        SVGPath().apply {
            content = "M4.5,10.5 L9,6 M6,5.8 H9.2 V9"
            fill = Color.TRANSPARENT
            stroke = EDGE
            strokeWidth = 1.2
            strokeLineCap = StrokeLineCap.ROUND
            strokeLineJoin = StrokeLineJoin.ROUND
        }
    return Group(Rectangle(16.0, 16.0, Color.TRANSPARENT), tile(1.0, 7.0, LOW), tile(7.0, 1.0, HIGH), arrow)
}
