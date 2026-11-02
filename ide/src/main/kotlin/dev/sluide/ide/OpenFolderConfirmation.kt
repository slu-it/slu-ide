package dev.sluide.ide

import dev.sluide.ide.conversions.confirmation
import javafx.scene.control.Alert
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.stage.Window

/** The question before a folder opens in a window whose shared database connections have work, listing [warnings]. */
internal fun openFolderQuestion(warnings: List<String>): String =
    "Opening the folder disconnects these databases:\n" + warnings.joinToString("\n")

/**
 * Asks whether to open a folder in a window whose shared database connections have work that opening loses: their
 * sessions close (ADR 0121). Waits for the answer; `true` to open. Cancel is the default, so Enter keeps them.
 */
internal fun askToOpenFolder(
    owner: Window?,
    warnings: List<String>,
): Boolean = openFolderConfirmation(owner, warnings).showAndWait().orElse(ButtonType.CANCEL) == OPEN_BUTTON

/** The dialog of [askToOpenFolder], not shown yet: Open, or Cancel, the default and focused button. */
internal fun openFolderConfirmation(
    owner: Window?,
    warnings: List<String>,
): Alert = confirmation(owner, openFolderQuestion(warnings), OPEN_BUTTON, "Open Folder")

internal val OPEN_BUTTON = ButtonType("Open", ButtonData.OK_DONE)
