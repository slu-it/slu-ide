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
 * finds a problem with the name, the dialog shows it and OK is disabled. [note], if given, may tell something about a
 * valid name (e.g. what it is saved as), shown apart from the problem.
 */
internal fun askForName(
    owner: Window?,
    title: String,
    initial: String,
    validate: (String) -> String?,
    note: ((String) -> String?)? = null,
): String? = NameDialog(owner, title, initial, validate, note).showAndWait().orElse(null)

/**
 * The dialog of [askForName]. The base name of [initial] (of its last `/`-separated part) is selected, so typing
 * replaces it but keeps the extension.
 */
internal class NameDialog(
    owner: Window?,
    title: String,
    initial: String,
    validate: (String) -> String?,
    noteOf: ((String) -> String?)? = null,
) : Dialog<String>() {
    val field = TextField(initial).apply { prefColumnCount = NAME_COLUMNS }
    val problem = Label()
    val note =
        Label().apply {
            // Its line is kept while empty, since the shown dialog does not grow when a note appears (its buttons were
            // cut off); a dialog without notes (New File, Rename) has none.
            isVisible = noteOf != null
            isManaged = noteOf != null
        }

    init {
        owner?.let(::initOwner)
        this.title = title
        headerText = null
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        dialogPane.content = VBox(SPACING, Label("Name:"), field, problem, note)
        val ok = dialogPane.lookupButton(ButtonType.OK)
        val check = {
            val found = validate(field.text)
            problem.text = found
            note.text = if (found == null) noteOf?.invoke(field.text) else null
            ok.isDisable = found != null
        }
        field.textProperty().addListener { _ -> check() }
        check()
        setResultConverter { button -> if (button == ButtonType.OK) field.text else null }
        setOnShown {
            // Later, since the enabled default button takes the focus once the dialog is shown.
            Platform.runLater {
                field.requestFocus()
                selection(initial).let { field.selectRange(it.first, it.last + 1) }
            }
        }
    }
}

/** The part of [initial] selected at first: the base name of its last `/`-separated part ([baseNameLength]). */
internal fun selection(initial: String): IntRange {
    val start = initial.lastIndexOf('/') + 1
    return start until start + baseNameLength(initial.substring(start))
}

/** The length of [name] without its extension; a leading dot does not start an extension (`.gitignore`). */
internal fun baseNameLength(name: String): Int = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length

private const val NAME_COLUMNS = 30
private const val SPACING = 6.0
