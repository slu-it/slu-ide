package dev.sluide.ide.conversions

import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.layout.Region
import javafx.stage.Window
import java.nio.file.Path

/**
 * The file the explorer's "Convert to" writes for [source]: beside it, with its base name and the first of the target
 * format's [extensions]. YAML gets `.yml` instead of `.yaml` when [siblings] (the names in the folder) has a `.yml`
 * file and no `.yaml` file.
 */
internal fun targetFile(
    source: Path,
    extensions: List<String>,
    siblings: Collection<String>,
): Path {
    val name = source.fileName.toString()
    val base = name.substringBeforeLast('.', name)
    var extension = extensions.first()
    if (extension == "yaml" && siblings.any { it.endsWith(".yml", ignoreCase = true) } &&
        siblings.none { it.endsWith(".yaml", ignoreCase = true) }
    ) {
        extension = "yml"
    }
    return source.resolveSibling("$base.$extension")
}

/** The question before replacing [file] with a conversion. */
internal fun overwriteQuestion(file: Path): String =
    "“${file.fileName}” exists already. Replace it with the converted file?"

/** What the user is told after a conversion of [source] to [format] that could not keep everything. */
internal fun notesMessage(
    source: Path,
    format: String,
    written: Path,
    notes: List<String>,
): String =
    "“${source.fileName}” was converted to $format as “${written.fileName}”, but not everything survived:\n" +
        notes.joinToString("\n") { "• $it" }

/** The question before converting [file] while a tab holds unsaved changes of it. */
internal fun convertSavedQuestion(file: Path): String =
    "“${file.fileName}” has unsaved changes. Convert to uses the saved file."

/** Asks whether to replace [file] in a dialog and waits for the answer; Cancel is the default, so Enter keeps it. */
internal fun askToOverwrite(
    owner: Window?,
    file: Path,
): Boolean = overwriteConfirmation(owner, file).showAndWait().orElse(ButtonType.CANCEL) == REPLACE_BUTTON

/** Asks whether to convert the saved [file] in a dialog and waits; Cancel is the default. */
internal fun askToConvertSaved(
    owner: Window?,
    file: Path,
): Boolean =
    confirmation(owner, convertSavedQuestion(file), CONVERT_SAVED_BUTTON).showAndWait().orElse(ButtonType.CANCEL) ==
        CONVERT_SAVED_BUTTON

/** The dialog of [askToOverwrite], not shown yet. */
internal fun overwriteConfirmation(
    owner: Window?,
    file: Path,
): Alert = confirmation(owner, overwriteQuestion(file), REPLACE_BUTTON)

/** A dialog asking [question] with [confirm] and Cancel, not shown yet. Cancel is the default button and has the focus. */
internal fun confirmation(
    owner: Window?,
    question: String,
    confirm: ButtonType,
): Alert =
    Alert(Alert.AlertType.CONFIRMATION, question, confirm, ButtonType.CANCEL).apply {
        owner?.let(::initOwner)
        title = "Convert"
        headerText = null
        dialogPane.minHeight = Region.USE_PREF_SIZE
        val cancel = dialogPane.lookupButton(ButtonType.CANCEL) as Button
        (dialogPane.lookupButton(confirm) as Button).isDefaultButton = false
        cancel.isDefaultButton = true
        // Later, since the dialog focuses a button of its own once shown.
        setOnShown { Platform.runLater(cancel::requestFocus) }
    }

/** Tells the user [message] about a finished conversion in a dialog and waits until it is closed. */
internal fun showConversionNotes(
    owner: Window?,
    message: String,
) {
    Alert(Alert.AlertType.INFORMATION, message)
        .apply {
            owner?.let(::initOwner)
            title = "Convert"
            headerText = null
            dialogPane.minHeight = Region.USE_PREF_SIZE
        }.showAndWait()
}

internal val REPLACE_BUTTON = ButtonType("Replace", ButtonBar.ButtonData.OK_DONE)

internal val CONVERT_SAVED_BUTTON = ButtonType("Convert Saved File", ButtonBar.ButtonData.OK_DONE)
