package dev.sluide.ide

import dev.sluide.projecttrust.ProjectTrustState
import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.stage.Window
import java.nio.file.Path

internal val TRUST_BUTTON = ButtonType("Trust", ButtonBar.ButtonData.OK_DONE)
internal val KEEP_OFF_BUTTON = ButtonType("Keep Off", ButtonBar.ButtonData.CANCEL_CLOSE)

/** The names of the tools that [state]'s findings keep off, e.g. "Git" or "Git and Build". */
internal fun toolsOff(state: ProjectTrustState): String {
    val names = state.flagged.map { it.name }
    return if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString() + " and " + names.last()
}

/** What the checks of [state] found, per tool, each finding with when it runs (ADR 0053). */
internal fun trustDetails(state: ProjectTrustState): String =
    state.flagged.joinToString("\n\n") { result ->
        val findings = result.findings
        val lines =
            if (findings == null) {
                listOf("  could not be checked")
            } else {
                // What is known to run first, then what Thoreau does not know.
                findings.sortedBy { it.note == null }.map { finding ->
                    "  ${finding.text}\n      ${finding.note ?: "not known to Thoreau"}"
                }
            }
        (listOf(result.name) + lines).joinToString("\n")
    }

/**
 * Asks whether to trust what the checks of [state] found in [folder]: `true` to trust it, `false` to keep the tools
 * off. Keep Off is the default button, also for Esc and closing the dialog.
 */
internal fun trustConfirmation(
    owner: Window?,
    folder: Path,
    state: ProjectTrustState,
): Alert =
    Alert(Alert.AlertType.WARNING, null, TRUST_BUTTON, KEEP_OFF_BUTTON).apply {
        owner?.let(::initOwner)
        title = "Trust Folder?"
        val name = folder.fileName ?: folder
        headerText =
            if (state.flagged.all { it.findings == null }) {
                "What $name would make ${toolsOff(state)} run could not be checked."
            } else {
                "$name defines commands that ${toolsOff(state)} would run."
            }
        val details =
            TextArea(trustDetails(state)).apply {
                isEditable = false
                isWrapText = false
                font = Font.font("Monospaced", font.size)
                styleClass += "trust-details"
                prefColumnCount = 80
                // One more row for the horizontal scroll bar of long lines.
                prefRowCount = (text.lines().size + 1).coerceIn(3, 15)
            }
        val stay = if (state.flagged.size == 1) "stays" else "stay"
        val advice =
            Label(
                "Trust the folder only if you set it up yourself or know where it comes from. " +
                    "Until then, ${toolsOff(state)} $stay off for it.",
            ).apply { isWrapText = true }
        dialogPane.content = VBox(8.0, details, advice)
        isResizable = true
        // Keep Off is the default: Enter does not trust by accident.
        (dialogPane.lookupButton(TRUST_BUTTON) as Button).isDefaultButton = false
        (dialogPane.lookupButton(KEEP_OFF_BUTTON) as Button).isDefaultButton = true
        setOnShown { Platform.runLater { dialogPane.lookupButton(KEEP_OFF_BUTTON).requestFocus() } }
    }

/** Shows [trustConfirmation] and waits for the answer: `true` to trust. */
internal fun askToTrust(
    owner: Window?,
    folder: Path,
    state: ProjectTrustState,
): Boolean = trustConfirmation(owner, folder, state).showAndWait().orElse(KEEP_OFF_BUTTON) == TRUST_BUTTON
