package dev.sluide.gitlog.operations

import dev.sluide.git.GitPick
import dev.sluide.git.GitResetMode
import dev.sluide.gitlog.ui.GIT_LOG_CSS
import dev.sluide.gitlog.ui.SHORT_HASH
import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.TextArea
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Window

internal val CHERRY_PICK_BUTTON = ButtonType("Cherry-Pick", ButtonBar.ButtonData.OK_DONE)
internal val REVERT_BUTTON = ButtonType("Revert", ButtonBar.ButtonData.OK_DONE)

/** Leaves a stopped cherry-pick or revert for the user to resolve. */
internal val RESOLVE_BUTTON = ButtonType("Resolve", ButtonBar.ButtonData.OK_DONE)
internal val ABORT_CHERRY_PICK_BUTTON = ButtonType("Abort Cherry-Pick", ButtonBar.ButtonData.OTHER)
internal val ABORT_REVERT_BUTTON = ButtonType("Abort Revert", ButtonBar.ButtonData.OTHER)

/** The button that confirms a cherry-pick or revert ([kind]) of a merge commit. */
internal fun pickButton(kind: GitPick) =
    when (kind) {
        GitPick.CHERRY_PICK -> CHERRY_PICK_BUTTON
        GitPick.REVERT -> REVERT_BUTTON
    }

/** The button that aborts a stopped cherry-pick or revert ([kind]). */
internal fun abortButton(kind: GitPick) =
    when (kind) {
        GitPick.CHERRY_PICK -> ABORT_CHERRY_PICK_BUTTON
        GitPick.REVERT -> ABORT_REVERT_BUTTON
    }

/** The title of the menu item and of the merge commit question: "Cherry-Pick", "Revert Commit". */
internal fun pickTitle(kind: GitPick) =
    when (kind) {
        GitPick.CHERRY_PICK -> "Cherry-Pick"
        GitPick.REVERT -> "Revert Commit"
    }

/**
 * Tells that a cherry-pick or revert stopped ([stop], ADR 0042): with the conflicted paths, git's message in the
 * expandable details; without conflicts (e.g. a hook failed), git's message. Resolve, the default, leaves it for the
 * user; "Abort Cherry-Pick" ([abortButton]) aborts it. Not shown yet.
 */
internal fun pickStoppedDialog(
    owner: Window?,
    stop: PickStop,
): Alert =
    Alert(Alert.AlertType.WARNING, null, RESOLVE_BUTTON, abortButton(stop.kind)).apply {
        owner?.let(::initOwner)
        title = "${stop.kind.title} Stopped"
        val label = stop.commit.take(SHORT_HASH)
        if (stop.conflicts.isEmpty()) {
            headerText = "${stop.kind.sentence} of $label stopped."
            dialogPane.content = StackPane(gitMessage(stop.message))
        } else {
            headerText = "${stop.kind.sentence} of $label stopped with conflicts in:"
            val paths =
                TextArea(stop.conflicts.joinToString("\n")).apply {
                    styleClass += "git-conflict-paths"
                    isEditable = false
                    prefColumnCount = PATH_COLUMNS
                    prefRowCount = stop.conflicts.size.coerceIn(PATH_ROWS_MIN, PATH_ROWS_MAX)
                }
            // In panes of their own: the dialog pane pads its content, which would pad the text inside the areas.
            dialogPane.content = VBox(paths)
            if (stop.message.isNotBlank()) dialogPane.expandableContent = StackPane(gitMessage(stop.message))
        }
        val resolve = dialogPane.lookupButton(RESOLVE_BUTTON) as Button
        (dialogPane.lookupButton(abortButton(stop.kind)) as Button).isDefaultButton = false
        resolve.isDefaultButton = true
        setOnShown { Platform.runLater(resolve::requestFocus) }
    }

/**
 * Asks how to reset the current branch (or a detached `HEAD`), as [description] says ("Reset branch 'main' (at
 * 5e6f7a8b) to 1a2b3c4d"): Soft, Mixed (at first) or Hard, each with what it does to the files; Hard's in a warning
 * style. Reset or Cancel.
 */
internal class ResetDialog(
    owner: Window?,
    title: String,
    description: String,
) : Dialog<GitResetMode>() {
    private val modes = ToggleGroup()

    val soft = mode("Soft", GitResetMode.SOFT)
    val mixed = mode("Mixed", GitResetMode.MIXED)
    val hard = mode("Hard", GitResetMode.HARD)

    init {
        owner?.let(::initOwner)
        this.title = title
        headerText = null
        dialogPane.styleClass += "git-reset"
        dialogPane.stylesheets += GIT_LOG_CSS
        dialogPane.buttonTypes.setAll(RESET_BUTTON, ButtonType.CANCEL)
        mixed.isSelected = true
        dialogPane.content =
            VBox(
                SPACING,
                Label(description).apply { styleClass += "git-reset-description" },
                soft,
                explanation("Files won't change; the differences will be staged for commit."),
                mixed,
                explanation("Files won't change; the differences won't be staged."),
                hard,
                explanation(
                    "Files will be changed to the commit's state. Local changes to tracked files will be lost.",
                ).apply { styleClass += "git-reset-warning" },
            )
        setResultConverter { button ->
            if (button ==
                RESET_BUTTON
            ) {
                modes.selectedToggle?.userData as? GitResetMode
            } else {
                null
            }
        }
    }

    private fun mode(
        text: String,
        mode: GitResetMode,
    ) = RadioButton(text).apply {
        styleClass += "git-reset-${mode.name.lowercase()}"
        toggleGroup = modes
        userData = mode
    }

    private fun explanation(text: String) =
        Label(text).apply {
            styleClass += "git-reset-explanation"
            isWrapText = true
        }
}

private const val SPACING = 6.0
private const val PATH_COLUMNS = 40
private const val PATH_ROWS_MIN = 2
private const val PATH_ROWS_MAX = 10
