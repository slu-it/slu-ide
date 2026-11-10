package dev.sluide.games.orbitguard.ui

import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import javafx.scene.shape.Polygon
import javafx.scene.shape.Rectangle

private val PLANET = Color.web("#e08a1e")
private val ORBIT = Color.web("#6c8cbf")
private val SHIP = Color.web("#2aa8d8")
private val SHIP_EDGE = Color.web("#0c4a66")

/** The Orbit Guard logo: a ship on an orbit around a planet, in a 16 by 16 box (a fresh node per call). */
internal fun orbitGuardLogo(): Node {
    val orbit =
        Circle(8.0, 8.0, 6.2).apply {
            fill = Color.TRANSPARENT
            stroke = ORBIT
            strokeWidth = 1.0
        }
    // Pointing along the orbit, at the top right of it.
    val ship =
        Polygon(0.0, -3.0, 2.4, 2.2, 0.0, 1.0, -2.4, 2.2).apply {
            fill = SHIP
            stroke = SHIP_EDGE
            strokeWidth = 0.6
            translateX = 12.4
            translateY = 3.6
            rotate = 50.0
        }
    return Group(Rectangle(16.0, 16.0, Color.TRANSPARENT), orbit, Circle(8.0, 8.0, 2.8, PLANET), ship)
}
