package dev.sluide.fileexplorer.actions

import javafx.application.Platform
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.VBox
import javafx.stage.Window

/**
 * Asks for the name of a file or folder, starting with [initial]; returns it, or `null` if cancelled. While [validate]
 * finds a problem with the name, the dialog shows it and OK is disabled.
 */
internal fun askForName(
    owner: Window?,
    title: String,
    initial: String,
    validate: (String) -> String?,
): String? = NameDialog(owner, title, initial, validate).showAndWait().orElse(null)

/** The dialog of [askForName]. The base name of [initial] is selected, so typing replaces it but keeps the extension. */
internal class NameDialog(
    owner: Window?,
    title: String,
    initial: String,
    validate: (String) -> String?,
) : Dialog<String>() {
    val field = TextField(initial).apply { prefColumnCount = NAME_COLUMNS }
    val problem = Label()

    init {
        owner?.let(::initOwner)
        this.title = title
        headerText = null
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        dialogPane.content = VBox(SPACING, Label("Name:"), field, problem)
        val ok = dialogPane.lookupButton(ButtonType.OK)
        val check = {
            val found = validate(field.text)
            problem.text = found
            ok.isDisable = found != null
        }
        field.textProperty().addListener { _ -> check() }
        check()
        setResultConverter { button -> if (button == ButtonType.OK) field.text else null }
        setOnShown {
            // Later, since the enabled default button takes the focus once the dialog is shown.
            Platform.runLater {
                field.requestFocus()
                field.selectRange(0, baseNameLength(initial))
            }
        }
    }
}

/** The length of [name] without its extension; a leading dot does not start an extension (`.gitignore`). */
internal fun baseNameLength(name: String): Int = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length

private const val NAME_COLUMNS = 30
private const val SPACING = 6.0
