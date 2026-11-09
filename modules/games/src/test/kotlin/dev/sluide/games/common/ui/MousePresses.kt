package dev.sluide.games.common.ui

import javafx.event.Event
import javafx.scene.Node
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent

/** Fires a primary-button MOUSE_PRESSED on [target]; call it on the FX thread. */
internal fun firePress(target: Node) {
    Event.fireEvent(
        target,
        MouseEvent(
            MouseEvent.MOUSE_PRESSED,
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
            false,
            false,
            true,
            null,
        ),
    )
}
