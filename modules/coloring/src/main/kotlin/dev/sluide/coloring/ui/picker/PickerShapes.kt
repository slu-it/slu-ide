package dev.sluide.coloring.ui.picker

import javafx.event.EventHandler
import javafx.scene.Node
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Pane
import javafx.scene.paint.CycleMethod
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.Stop

/** A gradient over the whole shape, left to right or top to bottom. */
internal fun gradient(
    horizontal: Boolean,
    vararg stops: Stop,
) = LinearGradient(
    0.0,
    0.0,
    if (horizontal) 1.0 else 0.0,
    if (horizontal) 0.0 else 1.0,
    true,
    CycleMethod.NO_CYCLE,
    *stops,
)

/** Gives the pane exactly this size. */
internal fun Pane.fixSize(
    width: Double,
    height: Double,
) {
    setMinSize(width, height)
    setPrefSize(width, height)
    setMaxSize(width, height)
}

/** Runs [action] when the primary button is pressed on this node and while it is dragged. */
internal fun Node.onPrimaryPressOrDrag(action: (MouseEvent) -> Unit) {
    val handler = EventHandler<MouseEvent> { e -> if (e.button == MouseButton.PRIMARY) action(e) }
    onMousePressed = handler
    onMouseDragged = handler
}
