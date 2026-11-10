package dev.sluide.games.blockbreaker.ui

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.shape.Rectangle
import javafx.scene.shape.SVGPath
import javafx.scene.shape.StrokeLineCap

private val BRICK = Color.web("#d6504a")
private val BRICK_EDGE = Color.web("#6e1f1b")
private val BALL = Color.web("#f7f7f7")
private val BALL_EDGE = Color.web("#4a5670")
private val TRAIL = Color.web("#e8962a")

/**
 * The Block Breaker logo: a ball bouncing off the corner of a tilted brick,
 * in a 16 by 16 box (a fresh node per call).
 */
internal fun blockBreakerLogo(): Node {
    val brick =
        Rectangle(1.0, 9.0, 8.0, 4.5).apply {
            fill = BRICK
            stroke = BRICK_EDGE
            strokeWidth = 0.8
            arcWidth = 1.5
            arcHeight = 1.5
            rotate = -22.0
        }
    val trail =
        SVGPath().apply {
            content = "M1.5,1.5 L5.5,5 L8.8,6.5"
            fill = Color.TRANSPARENT
            stroke = TRAIL
            strokeWidth = 1.0
            strokeLineCap = StrokeLineCap.ROUND
            strokeDashArray.addAll(1.0, 1.6)
        }
    val ball =
        Circle(12.0, 5.0, 2.6).apply {
            fill = BALL
            stroke = BALL_EDGE
            strokeWidth = 0.9
        }
    return Group(Rectangle(16.0, 16.0, Color.TRANSPARENT), trail, brick, ball)
}
