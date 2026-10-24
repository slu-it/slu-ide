package dev.sluide.previews

import javafx.event.EventType
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent

/** A primary-button mouse event of [type] at (1, 1), as the mouse makes it: only a press has the button down. */
internal fun primaryMouseEvent(type: EventType<MouseEvent>) =
    MouseEvent(
        type,
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
        type == MouseEvent.MOUSE_PRESSED,
        false,
        false,
        false,
        false,
        true,
        null,
    )
