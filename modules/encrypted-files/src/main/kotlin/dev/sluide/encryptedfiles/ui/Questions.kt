package dev.sluide.encryptedfiles.ui

import dev.sluide.encryptedfiles.LockChoice
import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.stage.Window

private val SAVE = ButtonType("Save", ButtonData.YES)
private val DONT_SAVE = ButtonType("Don't Save", ButtonData.NO)
private val SAVE_ALL_AND_LOCK = ButtonType("Save All and Lock", ButtonData.YES)
private val DISCARD_AND_LOCK = ButtonType("Discard and Lock", ButtonData.NO)
private val REPLACE = ButtonType("Replace", ButtonData.OK_DONE)

/** Asks [question] before locking one tab: Save, Don't Save or Cancel (the default; also when dismissed). */
internal fun showUnsavedQuestion(
    owner: Window?,
    question: String,
): LockChoice =
    when (ask(owner, "Lock", question, SAVE, DONT_SAVE)) {
        SAVE -> LockChoice.SAVE
        DONT_SAVE -> LockChoice.DISCARD
        else -> LockChoice.CANCEL
    }

/** Asks [question] before Lock All: Save All and Lock, Discard and Lock, or Cancel (the default). */
internal fun showLockAllQuestion(
    owner: Window?,
    question: String,
): LockChoice =
    when (ask(owner, "Lock All Encrypted Files", question, SAVE_ALL_AND_LOCK, DISCARD_AND_LOCK)) {
        SAVE_ALL_AND_LOCK -> LockChoice.SAVE
        DISCARD_AND_LOCK -> LockChoice.DISCARD
        else -> LockChoice.CANCEL
    }

/** Asks [question] before saving over a file changed elsewhere: Replace, or Cancel (the default). */
internal fun showReplaceQuestion(
    owner: Window?,
    question: String,
): Boolean = ask(owner, "Save", question, REPLACE) == REPLACE

/** A question with [answers] and Cancel, which is the default button and has the focus; `null` when dismissed. */
private fun ask(
    owner: Window?,
    dialogTitle: String,
    question: String,
    vararg answers: ButtonType,
): ButtonType? {
    val alert = Alert(Alert.AlertType.CONFIRMATION)
    alert.buttonTypes.setAll(*answers, ButtonType.CANCEL)
    alert.title = dialogTitle
    alert.headerText = question
    owner?.let(alert::initOwner)
    for (answer in answers) (alert.dialogPane.lookupButton(answer) as Button).isDefaultButton = false
    val cancel = alert.dialogPane.lookupButton(ButtonType.CANCEL) as Button
    cancel.isDefaultButton = true
    // Later, since the dialog focuses a button of its own once shown.
    alert.setOnShown { Platform.runLater(cancel::requestFocus) }
    return alert.showAndWait().orElse(null)
}
