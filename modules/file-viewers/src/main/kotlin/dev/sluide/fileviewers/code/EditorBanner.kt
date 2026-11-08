package dev.sluide.fileviewers.code

import javafx.beans.value.ObservableValue
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region

/**
 * A bar at the top of a view with [message] and [actions]. In a narrow view the message wraps and the buttons keep
 * their full size.
 */
internal fun banner(
    message: ObservableValue<String>,
    vararg actions: Button,
): HBox =
    HBox(
        Label().apply {
            textProperty().bind(message)
            isWrapText = true
            minWidth = 0.0
            minHeight = Region.USE_PREF_SIZE
        },
        Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
        *actions.onEach { it.minWidth = Region.USE_PREF_SIZE },
    ).apply {
        styleClass += "editor-banner"
        alignment = Pos.CENTER_LEFT
    }
