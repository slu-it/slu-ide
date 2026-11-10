package dev.sluide.games.minigolf.ui

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.paint.Color
import javafx.scene.shape.Ellipse
import javafx.scene.shape.Line
import javafx.scene.shape.Polygon
import javafx.scene.shape.Rectangle
import javafx.scene.shape.StrokeLineCap

private val FELT = Color.web("#2f9a57")
private val FELT_EDGE = Color.web("#184d2b")
private val CUP = Color.web("#050607")
private val POLE = Color.web("#8a5a32")
private val FLAG = Color.web("#e8453c")

/** The Mini Golf logo: a flag in a hole on a green, in a 16 by 16 box (a fresh node per call). */
internal fun miniGolfLogo(): Node {
    val green =
        Ellipse(8.0, 12.2, 7.0, 3.2).apply {
            fill = FELT
            stroke = FELT_EDGE
            strokeWidth = 0.8
        }
    val pole =
        Line(8.4, 2.0, 8.4, 12.2).apply {
            stroke = POLE
            strokeWidth = 1.1
            strokeLineCap = StrokeLineCap.ROUND
        }
    return Group(
        Rectangle(16.0, 16.0, Color.TRANSPARENT),
        green,
        Ellipse(8.4, 12.4, 2.2, 0.9).apply { fill = CUP },
        pole,
        Polygon(8.9, 2.2, 14.0, 4.4, 8.9, 6.6).apply { fill = FLAG },
    )
}
