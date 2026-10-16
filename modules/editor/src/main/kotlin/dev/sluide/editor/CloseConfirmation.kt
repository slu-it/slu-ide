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

/** What to do with unsaved changes before closing, answered by [askToSave]. */
enum class SaveChoice {
    /** Save them, then close. */
    SAVE,

    /** Close without saving them; before a checkout: check out anyway, keeping them. */
    DISCARD,

    /** Close nothing. */
    CANCEL,
}

/** The buttons of [saveConfirmation] besides Cancel. */
internal val SAVE_BUTTON = ButtonType("Save", ButtonBar.ButtonData.YES)
internal val DISCARD_BUTTON = ButtonType("Don't Save", ButtonBar.ButtonData.NO)

/**
 * Asks whether to save the unsaved changes of the [files] (their paths, listed one per line) before they close: Save,
 * Don't Save or Cancel. Cancel is also the answer when the dialog is dismissed. Blocks until the dialog is closed; call
 * it on the FX thread.
 */
internal fun askToSave(
    owner: Window?,
    files: List<String>,
): SaveChoice =
    when (saveConfirmation(owner, files).showAndWait().orElse(ButtonType.CANCEL)) {
        SAVE_BUTTON -> SaveChoice.SAVE
        DISCARD_BUTTON -> SaveChoice.DISCARD
        else -> SaveChoice.CANCEL
    }

/** The dialog of [askToSave], not shown yet. */
internal fun saveConfirmation(
    owner: Window?,
    files: List<String>,
): Alert =
    Alert(
        Alert.AlertType.CONFIRMATION,
        files.joinToString("\n"),
        SAVE_BUTTON,
        DISCARD_BUTTON,
        ButtonType.CANCEL,
    ).apply {
        owner?.let(::initOwner)
        title = "Unsaved Changes"
        headerText = if (files.size == 1) "Save the changes to this file?" else "Save the changes to these files?"
    }

/** The button of [checkoutSaveConfirmation] that checks out without saving, keeping the unsaved changes. */
internal val CHECK_OUT_ANYWAY_BUTTON = ButtonType("Check Out Anyway", ButtonBar.ButtonData.NO)

/**
 * Asks whether to save the unsaved changes of the [files] (their paths, one per line) before a checkout changes files
 * on disk: Save ([SaveChoice.SAVE]), Check Out Anyway ([SaveChoice.DISCARD], keeping the changes) or Cancel, also when
 * the dialog is dismissed. Blocks until the dialog is closed; call it on the FX thread.
 */
internal fun askToSaveBeforeCheckout(
    owner: Window?,
    files: List<String>,
): SaveChoice =
    when (checkoutSaveConfirmation(owner, files).showAndWait().orElse(ButtonType.CANCEL)) {
        SAVE_BUTTON -> SaveChoice.SAVE
        CHECK_OUT_ANYWAY_BUTTON -> SaveChoice.DISCARD
        else -> SaveChoice.CANCEL
    }

/** The dialog of [askToSaveBeforeCheckout], not shown yet. */
internal fun checkoutSaveConfirmation(
    owner: Window?,
    files: List<String>,
): Alert =
    Alert(
        Alert.AlertType.CONFIRMATION,
        files.joinToString("\n"),
        SAVE_BUTTON,
        CHECK_OUT_ANYWAY_BUTTON,
        ButtonType.CANCEL,
    ).apply {
        owner?.let(::initOwner)
        title = "Unsaved Changes"
        headerText =
            if (files.size == 1) {
                "This file has unsaved changes. Save it before checking out?"
            } else {
                "These files have unsaved changes. Save them before checking out?"
            }
    }
