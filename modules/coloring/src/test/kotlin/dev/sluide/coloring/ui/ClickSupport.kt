package dev.sluide.coloring.ui

import javafx.event.Event
import javafx.scene.Node
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent

/** Fires a `MOUSE_CLICKED` of [button] on [target]; run it on the FX thread (`robot.interact`). */
internal fun click(
    target: Node,
    button: MouseButton = MouseButton.PRIMARY,
) = Event.fireEvent(
    target,
    MouseEvent(
        MouseEvent.MOUSE_CLICKED,
        1.0,
        1.0,
        1.0,
        1.0,
        button,
        1,
        false,
        false,
        false,
        false,
        button == MouseButton.PRIMARY,
        false,
        button == MouseButton.SECONDARY,
        true,
        false,
        false,
        null,
    ),
)
