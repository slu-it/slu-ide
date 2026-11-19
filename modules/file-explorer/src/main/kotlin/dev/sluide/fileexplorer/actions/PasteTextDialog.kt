package dev.sluide.fileexplorer.actions

import dev.sluide.fileicons.FILE_ICONS_CSS
import dev.sluide.fileicons.fileIconFor
import dev.sluide.fileicons.show
import dev.sluide.texttypes.TextType
import dev.sluide.texttypes.TextTypes
import javafx.application.Platform
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.TextField
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.stage.Window
import org.kordamp.ikonli.javafx.FontIcon
import java.nio.file.Path

/**
 * Asks for the name and the type of pasted text (ADR 0188): the final name (name and extension), or `null` if
 * cancelled. [detected] is the preselected type; the name is checked against [folder].
 */
internal fun askForTextName(
    owner: Window?,
    initialName: String,
    detected: TextType,
    folder: Path,
): String? = PasteTextDialog(owner, initialName, detected, folder).showAndWait().orElse(null)

/**
 * The dialog of [askForTextName]: a name field holding the base name, all selected and focused, and a combo box of
 * [types] with the file-type icons, the [detected] one marked and preselected. The final name, shown as a note while
 * it is valid, is the name and the extension of the type ([finalTextName]); the logic is in `PasteTextName.kt`.
 */
internal class PasteTextDialog(
    owner: Window?,
    initialName: String,
    detected: TextType,
    folder: Path,
    types: List<TextType> = TextTypes.all,
) : Dialog<String>() {
    val field = TextField(initialName).apply { prefColumnCount = NAME_COLUMNS }
    val combo = ComboBox<TextType>()
    val problem = Label()

    // Its line is kept while empty, since the shown dialog does not grow when a note appears (ADR 0167).
    val note = Label()

    init {
        owner?.let(::initOwner)
        title = "Paste Text"
        dialogPane.buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        dialogPane.stylesheets += FILE_ICONS_CSS
        combo.items.setAll(types)
        combo.maxWidth = Double.MAX_VALUE
        combo.setCellFactory { TypeCell(detected) }
        combo.buttonCell = TypeCell(detected)
        combo.value = detected
        dialogPane.content =
            VBox(SPACING, Label("Name:"), field, Label("Type:"), combo, problem, note).apply {
                minHeight = Region.USE_PREF_SIZE
            }
        // As the other dialogs with content (docs/ui-conventions.md): the window is not sized below its content.
        dialogPane.minHeight = Region.USE_PREF_SIZE
        val ok = dialogPane.lookupButton(ButtonType.OK)
        val check = {
            val type = combo.value
            val found = textNameProblem(field.text, type, folder)
            problem.text = found
            note.text = if (found == null) textNameNote(field.text, type) else null
            ok.isDisable = found != null
        }
        field.textProperty().addListener { _ -> check() }
        combo.valueProperty().addListener { _ -> check() }
        check()
        setResultConverter { button ->
            if (button == ButtonType.OK) finalTextName(field.text, combo.value) else null
        }
        setOnShown {
            // Later, since the enabled default button takes the focus once the dialog is shown.
            Platform.runLater {
                field.requestFocus()
                // Explicit, not left to the toolkit's selection on a focus request; the selectedText assertion of
                // PasteTextDialogUiTest, which macOS CI runs too, shows it holds there.
                field.selectAll()
            }
        }
    }
}

/** A combo cell with the file-type icon of the type and its [textTypeLabel]. */
private class TypeCell(
    private val detected: TextType,
) : ListCell<TextType>() {
    private val icon = FontIcon().apply { iconSize = ICON_SIZE }

    override fun updateItem(
        item: TextType?,
        empty: Boolean,
    ) {
        super.updateItem(item, empty)
        if (item == null || empty) {
            text = null
            graphic = null
            return
        }
        icon.show(fileIconFor("x.${item.extension.orEmpty()}", directory = false, expanded = false) { true })
        text = textTypeLabel(item, detected)
        graphic = icon
    }
}

private const val NAME_COLUMNS = 30
private const val SPACING = 6.0
private const val ICON_SIZE = 16
