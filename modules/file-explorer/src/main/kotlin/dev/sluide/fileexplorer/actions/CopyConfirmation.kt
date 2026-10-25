package dev.sluide.fileexplorer.actions

import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.layout.Region
import javafx.stage.Window

/** What a question before copying entries says: the question and the details below it. */
internal data class CopyQuestion(
    val header: String,
    val details: String,
)

/**
 * Asks whether to paste copies although the files named [unsaved] (at least one) have unsaved changes: the copies get
 * what is saved.
 */
internal fun copyUnsavedQuestion(unsaved: List<String>): CopyQuestion {
    val single = unsaved.singleOrNull()
    return CopyQuestion(
        single?.let { "“$it” has unsaved changes. Copy it anyway?" }
            ?: "${unsaved.size} files have unsaved changes. Copy them anyway?",
        listOfNotNull(
            if (single == null) nameLines(unsaved.map { "“$it”" }) else null,
            if (single == null) {
                "The copies get what is saved in these files, without the unsaved changes."
            } else {
                "The copy gets what is saved in the file, without the unsaved changes."
            },
        ).joinToString("\n\n"),
    )
}

/**
 * Asks whether to copy entries into the folder named [folder], e.g. after a drop: [copies] are the names of the
 * entries (at least one) with the names their copies get there, [unsaved] the names of the files with unsaved changes
 * among them, whose copies get what is saved.
 */
internal fun copyDropQuestion(
    copies: List<Pair<String, String>>,
    folder: String,
    unsaved: List<String>,
): CopyQuestion {
    val header =
        copies.singleOrNull()?.let { "Copy “${it.first}” to “$folder”?" } ?: "Copy ${copies.size} entries to “$folder”?"
    val names =
        when {
            copies.size > 1 -> nameLines(copies.map(::copyLine))
            copies.single().let { it.first != it.second } -> "The copy is named “${copies.single().second}”."
            else -> null
        }
    val unsavedText =
        when (unsaved.size) {
            0 -> {
                null
            }

            1 -> {
                "“${unsaved.single()}” has unsaved changes; the copy gets what is saved."
            }

            else -> {
                "These files have unsaved changes; the copies get what is saved:\n" +
                    nameLines(unsaved.map { "“$it”" })
            }
        }
    return CopyQuestion(header, listOfNotNull(names, unsavedText).joinToString("\n\n"))
}

/** One entry of a copy question: its name, and the name of its copy if that differs. */
private fun copyLine(copy: Pair<String, String>): String =
    if (copy.first == copy.second) "“${copy.first}”" else "“${copy.first}” → “${copy.second}”"

/** Asks [question] in a dialog and waits for the answer; returns whether to copy. */
internal fun askToCopy(
    owner: Window?,
    question: CopyQuestion,
): Boolean = copyConfirmation(owner, question).showAndWait().orElse(ButtonType.CANCEL) == COPY_BUTTON

/** The dialog of [askToCopy], not shown yet. Cancel is the default button and has the focus, so Enter copies nothing. */
internal fun copyConfirmation(
    owner: Window?,
    question: CopyQuestion,
): Alert =
    Alert(Alert.AlertType.CONFIRMATION, question.details, COPY_BUTTON, ButtonType.CANCEL).apply {
        owner?.let(::initOwner)
        title = "Copy"
        headerText = question.header
        // Else the dialog keeps the height of one line of details and cuts the names off.
        dialogPane.minHeight = Region.USE_PREF_SIZE
        val cancel = dialogPane.lookupButton(ButtonType.CANCEL) as Button
        (dialogPane.lookupButton(COPY_BUTTON) as Button).isDefaultButton = false
        cancel.isDefaultButton = true
        // Later, since the dialog focuses a button of its own once shown.
        setOnShown { Platform.runLater(cancel::requestFocus) }
    }

/** The Copy button of [copyConfirmation]. */
internal val COPY_BUTTON = ButtonType("Copy", ButtonBar.ButtonData.OK_DONE)
