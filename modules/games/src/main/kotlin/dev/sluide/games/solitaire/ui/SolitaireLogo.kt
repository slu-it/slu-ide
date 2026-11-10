package dev.sluide.games.solitaire.ui

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.paint.Color
import javafx.scene.shape.Polygon
import javafx.scene.shape.Rectangle

private val CARD = Color.web("#f4f4f4")
private val OUTLINE = Color.web("#55627a")
private val PIP = Color.web("#d6504a")

/**
 * The Solitaire logo: two fanned cards, the front one with a red diamond,
 * in a 16 by 16 box (a fresh node per call).
 */
internal fun solitaireLogo(): Node {
    fun card(
        x: Double,
        angle: Double,
        vararg marks: Node,
    ) = Group(
        Rectangle(x, 2.5, 7.0, 11.0).apply {
            fill = CARD
            stroke = OUTLINE
            strokeWidth = 1.0
            arcWidth = 2.0
            arcHeight = 2.0
        },
        *marks,
    ).apply { rotate = angle }
    val diamond = Polygon(9.0, 5.5, 11.0, 8.0, 9.0, 10.5, 7.0, 8.0).apply { fill = PIP }
    return Group(Rectangle(16.0, 16.0, Color.TRANSPARENT), card(2.0, -14.0), card(7.0, 12.0, diamond))
}
