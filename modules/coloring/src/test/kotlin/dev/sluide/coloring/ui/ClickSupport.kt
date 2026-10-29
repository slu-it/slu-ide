package dev.sluide.coloring.ui

import javafx.event.Event
import javafx.scene.Node
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent

/** Fires a primary `MOUSE_CLICKED` on [target]; run it on the FX thread (`robot.interact`). */
internal fun clickPrimary(target: Node) =
    Event.fireEvent(
        target,
        MouseEvent(
            MouseEvent.MOUSE_CLICKED,
            1.0,
            1.0,
            1.0,
            1.0,
            MouseButton.PRIMARY,
            1,
            false,
            false,
            false,
            false,
            true,
            false,
            false,
            true,
            false,
            false,
            null,
        ),
    )
