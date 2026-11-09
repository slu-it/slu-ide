package dev.sluide.games.minigolf.ui

import dev.sluide.games.common.ui.FieldPlacement
import dev.sluide.games.minigolf.geometry.Polygon
import dev.sluide.games.minigolf.geometry.Vec
import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.Color
import javafx.scene.paint.CycleMethod
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.RadialGradient
import javafx.scene.paint.Stop

/**
 * Runs [block] with the gc transformed so that it draws in field units: translated by the placement offsets and scaled
 * by its scale. Mini Golf draws smooth vectors, so unlike the pixel-art games it does not snap to logical pixels. No
 * clip: at fractional scales even a rectangle clip sends every call through JavaFX's slow masked path.
 */
internal inline fun GraphicsContext.inField(
    placement: FieldPlacement,
    block: GraphicsContext.() -> Unit,
) {
    save()
    translate(placement.offsetX, placement.offsetY)
    scale(placement.scale, placement.scale)
    try {
        block()
    } finally {
        restore()
    }
}

/** Makes the outline of [polygon] the current path. */
internal fun GraphicsContext.polygonPath(polygon: Polygon) {
    beginPath()
    polygon.points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
    closePath()
}

internal fun GraphicsContext.fillCircle(
    center: Vec,
    radius: Double,
) = fillOval(center.x - radius, center.y - radius, radius * 2, radius * 2)

internal fun GraphicsContext.strokeCircle(
    center: Vec,
    radius: Double,
) = strokeOval(center.x - radius, center.y - radius, radius * 2, radius * 2)

internal fun gradient(
    from: Vec,
    to: Vec,
    vararg colors: Color,
): LinearGradient =
    LinearGradient(
        from.x,
        from.y,
        to.x,
        to.y,
        false,
        CycleMethod.NO_CYCLE,
        colors.mapIndexed { i, c -> Stop(i.toDouble() / (colors.size - 1), c) },
    )

/** A line from [from] to [to] with a filled arrowhead at [to], in the current stroke and fill colours. */
internal fun GraphicsContext.arrow(
    from: Vec,
    to: Vec,
    head: Double,
) {
    val direction = (to - from).normalized()
    val side = direction.perpendicular()
    val base = to - direction * head
    strokeLine(from.x, from.y, base.x + direction.x * 0.5, base.y + direction.y * 0.5)
    val left = base + side * (head * 0.55)
    val right = base - side * (head * 0.55)
    fillPolygon(doubleArrayOf(to.x, left.x, right.x), doubleArrayOf(to.y, left.y, right.y), 3)
}

/** A ball-like shading: [inner] at the upper left of [center], [outer] at the rim. */
internal fun radial(
    center: Vec,
    radius: Double,
    inner: Color,
    outer: Color,
) = RadialGradient(
    0.0,
    0.0,
    center.x - radius * 0.3,
    center.y - radius * 0.3,
    radius * 1.3,
    false,
    CycleMethod.NO_CYCLE,
    Stop(0.0, inner),
    Stop(1.0, outer),
)
