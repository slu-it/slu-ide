package dev.sluide.games.pipepuzzle.ui

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.shape.SVGPath
import javafx.scene.shape.StrokeLineCap

private val PIPE = Color.web("#2b9488")
private val PIPE_EDGE = Color.web("#0f4a45")
private val FLOW = Color.web("#bff3ec")

/** The Pipe Puzzle logo: an elbow pipe with a flange at each end, in a 16 by 16 box (a fresh node per call). */
internal fun pipePuzzleLogo(): Node {
    val elbow = "M3,15 V9 Q3,5 7,5 H15"

    fun stroke(
        color: Color,
        width: Double,
        cap: StrokeLineCap,
    ) = SVGPath().apply {
        content = elbow
        fill = Color.TRANSPARENT
        stroke = color
        strokeWidth = width
        strokeLineCap = cap
    }

    fun flange(
        x: Double,
        y: Double,
        w: Double,
        h: Double,
    ) = Rectangle(x, y, w, h).apply {
        fill = PIPE
        stroke = PIPE_EDGE
        strokeWidth = 0.7
    }
    return Group(
        Rectangle(16.0, 16.0, Color.TRANSPARENT),
        stroke(PIPE_EDGE, 5.4, StrokeLineCap.BUTT),
        stroke(PIPE, 4.0, StrokeLineCap.BUTT),
        stroke(FLOW, 1.0, StrokeLineCap.BUTT),
        flange(0.8, 13.2, 4.4, 2.0),
        flange(13.2, 2.8, 2.0, 4.4),
    )
}
