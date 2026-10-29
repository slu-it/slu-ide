package dev.sluide.gitlog.operations

import dev.sluide.gitlog.ui.GIT_LOG_CSS
import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.VBox
import javafx.stage.Window

internal val STASH_BUTTON = ButtonType("Stash", ButtonBar.ButtonData.OK_DONE)

/**
 * Asks how to stash as [prompt] says (ADR 0107): "Stash local changes of 'feature'" ("…of HEAD" when detached; "Stash 3
 * checked files of 'feature'" for the Commit tab's), an optional one-line message and, unless only checked files are
 * stashed (which include their unversioned ones anyway), "Include untracked files", unchecked; ignored files never.
 * Stash (the default) or Cancel; the result is the [StashChanges] to run.
 */
internal class StashDialog(
    owner: Window?,
    private val prompt: StashPrompt,
) : Dialog<StashChanges>() {
    val message =
        TextField().apply {
            styleClass += "git-stash-message"
            promptText = "Message (optional)"
            prefColumnCount = MESSAGE_COLUMNS
        }

    val untracked =
        CheckBox("Include untracked files").apply {
            styleClass += "git-stash-untracked"
            isVisible = prompt.checked == null
            isManaged = isVisible
        }

    init {
        owner?.let(::initOwner)
        title = "Stash Changes"
        headerText = stashHeader(prompt)
        dialogPane.styleClass += "git-stash"
        dialogPane.stylesheets += GIT_LOG_CSS
        dialogPane.buttonTypes.setAll(STASH_BUTTON, ButtonType.CANCEL)
        dialogPane.content = VBox(SPACING, message, untracked)
        (dialogPane.lookupButton(STASH_BUTTON) as Button).isDefaultButton = true
        setResultConverter { button -> if (button == STASH_BUTTON) value() else null }
        setOnShown { Platform.runLater(message::requestFocus) }
    }

    /** What the dialog says to stash now. */
    fun value(): StashChanges =
        StashChanges(
            message.text.trim().ifEmpty { null },
            prompt.checked?.includeUntracked ?: untracked.isSelected,
            prompt.checked?.paths,
        )

    private companion object {
        const val MESSAGE_COLUMNS = 40
        const val SPACING = 8.0
    }
}

/**
 * Tells that popping or applying a stash gave conflicts ([conflict], ADR 0107), like the Merge Stopped dialog without
 * its Abort: the conflicted paths and that the stash is kept until dropped; OK only.
 */
internal fun stashConflictsDialog(
    owner: Window?,
    conflict: StashConflict,
): Alert =
    Alert(Alert.AlertType.WARNING, null, ButtonType.OK).apply {
        owner?.let(::initOwner)
        title = "Stash Conflicts"
        headerText = stashConflictHeader(conflict)
        val paths =
            TextArea(conflict.paths.joinToString("\n")).apply {
                styleClass += "git-conflict-paths"
                isEditable = false
                prefColumnCount = PATH_COLUMNS
                prefRowCount = conflict.paths.size.coerceIn(PATH_ROWS_MIN, PATH_ROWS_MAX)
            }
        val note = Label(stashConflictNote(conflict)).apply { isWrapText = true }
        dialogPane.content = VBox(NOTE_SPACING, paths, note)
    }

private const val PATH_COLUMNS = 40
private const val PATH_ROWS_MIN = 2
private const val PATH_ROWS_MAX = 10
private const val NOTE_SPACING = 8.0
