package dev.sluide.todos.view

import dev.sluide.todos.Todo
import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.stage.Window

/** The Delete button of [deleteConfirmation]. */
internal val DELETE_BUTTON = ButtonType("Delete", ButtonBar.ButtonData.OK_DONE)

/** Asks whether to delete [todo] and waits for the answer. */
internal fun askToDelete(
    owner: Window?,
    todo: Todo,
): Boolean = deleteConfirmation(owner, todo).showAndWait().orElse(ButtonType.CANCEL) == DELETE_BUTTON

/** The dialog of [askToDelete], not shown yet. Cancel is the default button and has the focus: Enter keeps the TODO. */
internal fun deleteConfirmation(
    owner: Window?,
    todo: Todo,
): Alert =
    Alert(Alert.AlertType.CONFIRMATION, "", DELETE_BUTTON, ButtonType.CANCEL).apply {
        owner?.let(::initOwner)
        title = "Delete TODO"
        headerText = deleteHeader(todo.summary)
        contentText = null
        val cancel = dialogPane.lookupButton(ButtonType.CANCEL) as Button
        (dialogPane.lookupButton(DELETE_BUTTON) as Button).isDefaultButton = false
        cancel.isDefaultButton = true
        // Later, since the dialog focuses a button of its own once shown.
        setOnShown { Platform.runLater(cancel::requestFocus) }
    }

/** The header of the delete confirmation for a TODO with [summary]; long summaries are shortened. */
internal fun deleteHeader(summary: String): String {
    val shown = if (summary.length > MAX_SUMMARY_SHOWN) summary.take(MAX_SUMMARY_SHOWN - 1) + "…" else summary
    return "Delete TODO “$shown”?"
}

private const val MAX_SUMMARY_SHOWN = 60
