package dev.sluide.fileexplorer.actions

import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.layout.Region
import javafx.stage.Window

/**
 * What the confirmation before moving entries says: the question, the details below it, and whether some entries are
 * skipped because their names exist in the folder already ([skips]; the button then reads "Move the others").
 */
internal data class MoveQuestion(
    val header: String,
    val details: String,
    val skips: Boolean,
)

/**
 * Asks whether to move the entries named [moving] (at least one) into the folder named [folder]; those named [skipped]
 * exist there already and stay where they are.
 */
internal fun moveQuestion(
    moving: List<String>,
    skipped: List<String>,
    folder: String,
): MoveQuestion {
    val header =
        moving.singleOrNull()?.let { "Move “$it” to “$folder”?" } ?: "Move ${moving.size} entries to “$folder”?"
    val names = if (moving.size > 1) nameLines(moving.map { "“$it”" }) else null
    val skipping =
        when (skipped.size) {
            0 -> null
            1 -> "“${skipped.single()}” already exists in “$folder” and is skipped."
            else -> "These already exist in “$folder” and are skipped:\n" + nameLines(skipped.map { "“$it”" })
        }
    return MoveQuestion(header, listOfNotNull(names, skipping).joinToString("\n\n"), skipped.isNotEmpty())
}

/** Asks [question] in a dialog and waits for the answer; returns whether to move. */
internal fun askToMove(
    owner: Window?,
    question: MoveQuestion,
): Boolean = moveConfirmation(owner, question).showAndWait().orElse(ButtonType.CANCEL) == moveButton(question)

/**
 * The dialog of [askToMove], not shown yet. Cancel is the default button and has the focus, so Enter moves nothing.
 */
internal fun moveConfirmation(
    owner: Window?,
    question: MoveQuestion,
): Alert {
    val move = moveButton(question)
    return Alert(Alert.AlertType.CONFIRMATION, question.details, move, ButtonType.CANCEL).apply {
        owner?.let(::initOwner)
        title = "Move"
        headerText = question.header
        // Else the dialog keeps the height of one line of details and cuts the names off.
        dialogPane.minHeight = Region.USE_PREF_SIZE
        val cancel = dialogPane.lookupButton(ButtonType.CANCEL) as Button
        (dialogPane.lookupButton(move) as Button).isDefaultButton = false
        cancel.isDefaultButton = true
        // Later, since the dialog focuses a button of its own once shown.
        setOnShown { Platform.runLater(cancel::requestFocus) }
    }
}

/** The button of [moveConfirmation] that moves: [MOVE_OTHERS_BUTTON] if the question skips entries. */
internal fun moveButton(question: MoveQuestion): ButtonType = if (question.skips) MOVE_OTHERS_BUTTON else MOVE_BUTTON

/** The Move button of [moveConfirmation]. */
internal val MOVE_BUTTON = ButtonType("Move", ButtonBar.ButtonData.OK_DONE)

/** The button of [moveConfirmation] that moves the entries whose names are free in the folder. */
internal val MOVE_OTHERS_BUTTON = ButtonType("Move the others", ButtonBar.ButtonData.OK_DONE)
