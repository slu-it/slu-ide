package dev.sluide.fileviewers.code

import javafx.beans.value.ObservableValue
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region

/** A bar at the top of a view with [message] and [actions]. */
internal fun banner(
    message: ObservableValue<String>,
    vararg actions: Button,
): HBox =
    HBox(
        Label().apply {
            textProperty().bind(message)
        },
        Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
        *actions,
    ).apply {
        styleClass += "editor-banner"
        alignment = Pos.CENTER_LEFT
    }
