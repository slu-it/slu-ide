package dev.sluide.games.wordguess.ui

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.shape.SVGPath
import javafx.scene.shape.StrokeLineCap
import javafx.scene.shape.StrokeLineJoin

private val BUBBLE = Color.web("#3f8fd8")
private val LETTER = Color.web("#f7fbff")

/** The Word Guess logo: a speech bubble holding a letter, in a 16 by 16 box (a fresh node per call). */
internal fun wordGuessLogo(): Node {
    val bubble =
        SVGPath().apply {
            content =
                "M3,1.5 H13 A2,2 0 0 1 15,3.5 V9.5 A2,2 0 0 1 13,11.5 H8.5 L5,15 V11.5 H3 " +
                "A2,2 0 0 1 1,9.5 V3.5 A2,2 0 0 1 3,1.5 Z"
            fill = BUBBLE
        }
    val letter =
        SVGPath().apply {
            content = "M5,9.5 L8,3.5 L11,9.5 M6.2,7.6 H9.8"
            fill = Color.TRANSPARENT
            stroke = LETTER
            strokeWidth = 1.4
            strokeLineCap = StrokeLineCap.ROUND
            strokeLineJoin = StrokeLineJoin.ROUND
        }
    return Group(Rectangle(16.0, 16.0, Color.TRANSPARENT), bubble, letter)
}
