package dev.sluide.fileexplorer

import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.stage.Window
import java.nio.file.Path

/** What the confirmation before deleting an entry says: the question and the consequences below it. */
internal data class DeleteQuestion(
    val header: String,
    val details: String,
)

/** The kind of entry to delete, as named in the question. */
internal enum class EntryKind(
    val noun: String,
) {
    FILE("file"),
    FOLDER("folder"),
    LINK("link"),
}

/** Asks whether to delete [path], a [kind] of entry with [unsaved] files with unsaved changes in or at it. */
internal fun deleteQuestion(
    path: Path,
    kind: EntryKind,
    unsaved: Int,
): DeleteQuestion {
    val content = if (kind == EntryKind.FOLDER) " and everything in it" else ""
    val lost =
        when (unsaved) {
            0 -> null
            1 -> "1 file with unsaved changes will be lost."
            else -> "$unsaved files with unsaved changes will be lost."
        }
    return DeleteQuestion(
        "Delete the ${kind.noun} “${path.fileName}”$content?",
        listOfNotNull(lost, "It is deleted permanently, not moved to the trash. This cannot be undone.")
            .joinToString("\n"),
    )
}

/** Asks [question] in a dialog and waits for the answer; returns whether to delete. */
internal fun askToDelete(
    owner: Window?,
    question: DeleteQuestion,
): Boolean = deleteConfirmation(owner, question).showAndWait().orElse(ButtonType.CANCEL) == DELETE_BUTTON

/**
 * The dialog of [askToDelete], not shown yet. Cancel is the default button and has the focus, so Enter does not delete
 * anything for good.
 */
internal fun deleteConfirmation(
    owner: Window?,
    question: DeleteQuestion,
): Alert =
    Alert(Alert.AlertType.CONFIRMATION, question.details, DELETE_BUTTON, ButtonType.CANCEL).apply {
        owner?.let(::initOwner)
        title = "Delete"
        headerText = question.header
        val cancel = dialogPane.lookupButton(ButtonType.CANCEL) as Button
        (dialogPane.lookupButton(DELETE_BUTTON) as Button).isDefaultButton = false
        cancel.isDefaultButton = true
        // Later, since the dialog focuses a button of its own once shown.
        setOnShown { Platform.runLater(cancel::requestFocus) }
    }

/** The Delete button of [deleteConfirmation]. */
internal val DELETE_BUTTON = ButtonType("Delete", ButtonBar.ButtonData.OK_DONE)
