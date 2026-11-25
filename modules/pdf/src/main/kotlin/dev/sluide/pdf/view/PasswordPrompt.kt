package dev.sluide.pdf.view

import javafx.event.ActionEvent
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.layout.VBox
import javafx.stage.Window

/**
 * What the view asks the password prompt for the file [fileName]: [attempt] tries a password (the view opens the PDF
 * with it off the FX thread) and answers on the FX thread whether the prompt is done (`false`: a wrong password);
 * [cancelled] is called when the user gives up. The prompt is owned by [owner].
 */
internal class PasswordRequest(
    val owner: Window?,
    val fileName: String,
    val attempt: (password: CharArray, done: (accepted: Boolean) -> Unit) -> Unit,
    val cancelled: () -> Unit,
)

/**
 * The password dialog of the PDF view, modelled on the HTTP client's passphrase prompt: "Open PDF", the file name, a
 * password field, Open and Cancel. Open tries the password while the dialog stays open (the button disabled until the
 * answer); a wrong one shows "Wrong password." under the field. The typed chars are zeroed once handed over.
 */
internal object PasswordPrompt {
    /** Shows the dialog for [request] without waiting; the function returned closes it. */
    fun show(request: PasswordRequest): () -> Unit {
        val dialog = dialog(request)
        dialog.show()
        return dialog::close
    }

    val OPEN = ButtonType("Open", ButtonData.OK_DONE)

    fun dialog(request: PasswordRequest): Dialog<ButtonType> {
        val field = PasswordField().apply { styleClass += "pdf-password" }
        val wrong =
            Label("Wrong password.").apply {
                styleClass += "pdf-wrong-password"
                isVisible = false
                isManaged = false
            }
        return Dialog<ButtonType>().apply {
            request.owner?.let(::initOwner)
            title = "Open PDF"
            headerText = null
            dialogPane.content = VBox(8.0, Label("${request.fileName} is password-protected."), field, wrong)
            dialogPane.buttonTypes.setAll(OPEN, ButtonType.CANCEL)
            var accepted = false
            val open = dialogPane.lookupButton(OPEN) as Button
            open.addEventFilter(ActionEvent.ACTION) { event ->
                // The dialog stays open until the password is known to open the PDF.
                event.consume()
                val typed = CharArray(field.characters.length) { field.characters[it] }
                open.isDisable = true
                request.attempt(typed) { done ->
                    open.isDisable = false
                    if (done) {
                        accepted = true
                        result = OPEN
                        close()
                    } else {
                        wrong.isVisible = true
                        wrong.isManaged = true
                        field.selectAll()
                        field.requestFocus()
                    }
                }
                typed.fill('\u0000')
            }
            setOnShown { field.requestFocus() }
            setOnHidden {
                field.clear()
                if (!accepted) request.cancelled()
            }
        }
    }
}
