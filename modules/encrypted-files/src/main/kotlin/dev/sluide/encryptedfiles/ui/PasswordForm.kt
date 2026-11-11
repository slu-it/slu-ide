package dev.sluide.encryptedfiles.ui

import javafx.application.Platform
import javafx.event.ActionEvent
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.layout.GridPane
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Window

private const val DIALOG_WIDTH = 380.0

internal const val NEW_PASSWORD_WARNING = "The password is stored nowhere. If you forget it, the content is lost."
internal const val CHANGE_PASSWORD_NOTE =
    "The current text, including unsaved changes, is saved under the new password."

/**
 * The shared dialog of `newPasswordDialog` and Change Password: fields (with Current if [withCurrent]), the hint, the
 * reason [confirmText] is disabled, and [note]. On confirm [start] gets the passwords as arrays (it owns and zeroes
 * them; `current` is empty without [withCurrent]) and the fields are cleared, except the new password and its repeat
 * of a dialog with a current password, which a wrong current password should not make type again (they are cleared
 * when the dialog closes). [start] reports through `done(error, close)` on the FX thread: `null` closes the dialog
 * with success; a text is shown and the buttons are enabled again, or the dialog closes without success if `close`.
 */
internal fun showPasswordDialog(
    owner: Window?,
    title: String,
    withCurrent: Boolean,
    note: String,
    confirmText: String,
    busyText: String,
    start: (current: CharArray, new: CharArray, done: (error: String?, close: Boolean) -> Unit) -> Unit,
): Boolean {
    val dialog = Dialog<Boolean>()
    dialog.title = title
    owner?.let(dialog::initOwner)
    val confirmType = ButtonType(confirmText, ButtonData.OK_DONE)
    dialog.dialogPane.buttonTypes.setAll(confirmType, ButtonType.CANCEL)
    val confirm = dialog.dialogPane.lookupButton(confirmType) as Button
    val cancel = dialog.dialogPane.lookupButton(ButtonType.CANCEL) as Button

    val currentField = passwordField("Current password", "password-current")
    val newField = passwordField(if (withCurrent) "New password" else "Password", "password-new")
    val repeatField = passwordField("Repeat", "password-repeat")
    val fields = listOfNotNull(currentField.takeIf { withCurrent }, newField, repeatField)
    val hint = Label().apply { id = "password-hint" }
    val problem = Label().apply { id = "password-problem" }
    val status = Label().apply { id = "password-status" }
    val noteLabel = Label(note).apply { isWrapText = true }

    val grid =
        GridPane().apply {
            hgap = 8.0
            vgap = 6.0
            fields.forEachIndexed { row, field ->
                // The labels keep their full width; the fields take what is left.
                add(Label(field.promptText).apply { minWidth = Region.USE_PREF_SIZE }, 0, row)
                add(field, 1, row)
            }
            add(hint, 1, fields.size)
        }
    dialog.dialogPane.content =
        VBox(10.0, grid, problem, noteLabel, status).apply {
            padding = Insets(8.0)
            prefWidth = DIALOG_WIDTH
        }

    val model = PasswordEntryModel(requireCurrent = withCurrent)
    var busy = false
    var succeeded = false

    fun refresh() {
        model.update(newField.text, repeatField.text, currentField.text)
        hint.text = model.hint
        problem.text = model.problem.orEmpty()
        confirm.isDisable = busy || !model.valid
        cancel.isDisable = busy
        fields.forEach { it.isDisable = busy }
    }
    for (field in fields) field.textProperty().subscribe { _ -> refresh() }

    fun finish(
        error: String?,
        close: Boolean,
    ) {
        busy = false
        if (error == null) {
            succeeded = true
            dialog.result = true
        } else if (close) {
            dialog.result = false
        } else {
            status.text = error
            refresh()
            (if (withCurrent) currentField else newField).requestFocus()
        }
    }

    fun submit() {
        if (busy || !model.valid) return
        val current = currentField.text.toCharArray()
        val new = newField.text.toCharArray()
        (if (withCurrent) listOf(currentField) else fields).forEach(::wipeField)
        busy = true
        status.text = busyText
        refresh()
        start(current, new, ::finish)
    }

    // The default button must not close the dialog: it stays until the work is done.
    confirm.addEventFilter(ActionEvent.ACTION) { event ->
        event.consume()
        submit()
    }
    dialog.setOnCloseRequest { event -> if (busy) event.consume() }
    dialog.setOnHidden { fields.forEach(::wipeField) }
    dialog.setResultConverter { succeeded }
    dialog.setOnShown { Platform.runLater { fields.first().requestFocus() } }
    refresh()
    return dialog.showAndWait().orElse(false)
}

private fun passwordField(
    prompt: String,
    fieldId: String,
) = PasswordField().apply {
    promptText = prompt
    id = fieldId
    prefColumnCount = 22
}

/**
 * Clears [field] after overwriting its text with same-length filler. JavaFX keeps the text in a StringBuilder that
 * `clear()` only shortens, so the typed password would stay in its backing array; `setText` replaces the characters
 * in place first.
 */
internal fun wipeField(field: PasswordField) {
    field.text = "*".repeat(field.length)
    field.clear()
}
