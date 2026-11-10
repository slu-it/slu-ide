package dev.sluide.games.common.ui

import javafx.event.Event
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent

/** Fires a primary-button MOUSE_PRESSED on [target]; call it on the FX thread. */
internal fun firePress(target: Node) = fireMouse(MouseEvent.MOUSE_PRESSED, target, 1.0, 1.0)

/**
 * Fires a [type] mouse event of [button] at [target] with the scene coordinates [sceneX], [sceneY]; the handlers get
 * the coordinates relative to their node. [primaryDown] is whether the primary button is held (by default only for a
 * primary event). Call it on the FX thread.
 */
internal fun fireMouse(
    type: EventType<MouseEvent>,
    target: Node,
    sceneX: Double,
    sceneY: Double,
    button: MouseButton = MouseButton.PRIMARY,
    primaryDown: Boolean = button == MouseButton.PRIMARY,
) {
    Event.fireEvent(
        target,
        MouseEvent(
            type,
            sceneX,
            sceneY,
            sceneX,
            sceneY,
            button,
            1,
            false,
            false,
            false,
            false,
            primaryDown,
            false,
            button == MouseButton.SECONDARY,
            false,
            false,
            true,
            null,
        ),
    )
}
