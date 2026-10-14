package dev.sluide.editor

import javafx.scene.control.Alert
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.stage.Window

/** The button of [closeConfirmation] that confirms closing. */
internal val CLOSE_BUTTON = ButtonType("Close", ButtonBar.ButtonData.OK_DONE)

/**
 * Asks whether to close something that still runs, e.g. tabs or a window with running terminals: a confirmation
 * dialog titled [title] with the question [header] above the [warnings], one per line. Returns `true` if the user
 * chose Close, `false` for Cancel or when the dialog was dismissed. Blocks until the dialog is closed; call it on the
 * FX thread.
 */
fun askToClose(
    owner: Window?,
    title: String,
    header: String,
    warnings: List<String>,
): Boolean = closeConfirmation(owner, title, header, warnings).showAndWait().orElse(ButtonType.CANCEL) == CLOSE_BUTTON

/** The dialog of [askToClose], not shown yet. */
internal fun closeConfirmation(
    owner: Window?,
    title: String,
    header: String,
    warnings: List<String>,
): Alert =
    Alert(Alert.AlertType.CONFIRMATION, warnings.joinToString("\n"), CLOSE_BUTTON, ButtonType.CANCEL).apply {
        owner?.let(::initOwner)
        this.title = title
        headerText = header
    }
