package dev.sluide.encryptedfiles.ui

import javafx.event.Event
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent

/** What counts as activity in an unlocked tab (the idle lock). */
enum class Activity { KEY, MOUSE, SCROLL }

internal fun activityEvent(activity: Activity): Event =
    when (activity) {
        Activity.KEY -> {
            KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.A, false, false, false, false)
        }

        Activity.MOUSE -> {
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
                false,
                null,
            )
        }

        Activity.SCROLL -> {
            ScrollEvent(
                ScrollEvent.SCROLL,
                1.0,
                1.0,
                1.0,
                1.0,
                false,
                false,
                false,
                false,
                false,
                false,
                0.0,
                40.0,
                0.0,
                40.0,
                ScrollEvent.HorizontalTextScrollUnits.NONE,
                0.0,
                ScrollEvent.VerticalTextScrollUnits.NONE,
                0.0,
                0,
                null,
            )
        }
    }
