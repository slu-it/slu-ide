package dev.sluide.todos.view

import dev.sluide.todos.Todo
import dev.sluide.todos.TodoFolder
import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.stage.Window

/** The Delete button of [deleteConfirmation] and of the confirmation for an empty folder. */
internal val DELETE_BUTTON = ButtonType("Delete", ButtonBar.ButtonData.OK_DONE)

/** The button of the confirmation for a folder with TODOs that deletes the folder and its TODOs. */
internal val DELETE_ALL_BUTTON = ButtonType("Delete All", ButtonBar.ButtonData.OK_DONE)

/** The button of the confirmation for a folder with TODOs that deletes only the folder. */
internal val KEEP_TODOS_BUTTON = ButtonType("Keep TODOs", ButtonBar.ButtonData.APPLY)

/** What to delete with a folder. */
internal enum class FolderDeletion { ALL, KEEP_TODOS }

/** Asks whether to delete [todo] and waits for the answer. */
internal fun askToDelete(
    owner: Window?,
    todo: Todo,
): Boolean = deleteConfirmation(owner, todo).showAndWait().orElse(ButtonType.CANCEL) == DELETE_BUTTON

/** Asks how to delete [folder] and waits for the answer; null = cancelled. */
internal fun askToDeleteFolder(
    owner: Window?,
    folder: TodoFolder,
): FolderDeletion? =
    when (folderDeletionConfirmation(owner, folder).showAndWait().orElse(ButtonType.CANCEL)) {
        DELETE_BUTTON, DELETE_ALL_BUTTON -> FolderDeletion.ALL
        KEEP_TODOS_BUTTON -> FolderDeletion.KEEP_TODOS
        else -> null
    }

/** The dialog of [askToDelete], not shown yet. Cancel is the default button and has the focus: Enter keeps the TODO. */
internal fun deleteConfirmation(
    owner: Window?,
    todo: Todo,
): Alert = confirmation(owner, "Delete TODO", deleteHeader(todo.summary), null, DELETE_BUTTON)

/** The dialog of [askToDeleteFolder], not shown yet; Cancel is the default button, as for a TODO. */
internal fun folderDeletionConfirmation(
    owner: Window?,
    folder: TodoFolder,
): Alert =
    if (folder.todos.isEmpty()) {
        confirmation(owner, "Delete Folder", deleteFolderHeader(folder.name, 0), null, DELETE_BUTTON)
    } else {
        confirmation(
            owner,
            "Delete Folder",
            deleteFolderHeader(folder.name, folder.todos.size),
            "Keep TODOs moves them out of the folder.",
            DELETE_ALL_BUTTON,
            KEEP_TODOS_BUTTON,
        )
    }

private fun confirmation(
    owner: Window?,
    title: String,
    header: String,
    content: String?,
    vararg buttons: ButtonType,
): Alert =
    Alert(Alert.AlertType.CONFIRMATION, "", *buttons, ButtonType.CANCEL).apply {
        owner?.let(::initOwner)
        this.title = title
        headerText = header
        contentText = content
        val cancel = dialogPane.lookupButton(ButtonType.CANCEL) as Button
        buttons.forEach { (dialogPane.lookupButton(it) as Button).isDefaultButton = false }
        cancel.isDefaultButton = true
        // Later, since the dialog focuses a button of its own once shown.
        setOnShown { Platform.runLater(cancel::requestFocus) }
    }

/** The header of the delete confirmation for a TODO with [summary]; long summaries are shortened. */
internal fun deleteHeader(summary: String): String = "Delete TODO “${shortened(summary)}”?"

/** The header of the delete confirmation for the folder [name] holding [todoCount] TODOs. */
internal fun deleteFolderHeader(
    name: String,
    todoCount: Int,
): String {
    val folder = "Delete folder “${shortened(name)}”"
    return when (todoCount) {
        0 -> "$folder?"
        1 -> "$folder with its 1 TODO?"
        else -> "$folder with its $todoCount TODOs?"
    }
}

private fun shortened(text: String): String {
    val tooLong = text.length > MAX_SUMMARY_SHOWN
    return if (tooLong) text.take(MAX_SUMMARY_SHOWN - 1) + "…" else text
}

private const val MAX_SUMMARY_SHOWN = 60
