package dev.sluide.ide.help

import dev.sluide.ide.APP_VERSION
import dev.sluide.ide.appTitle
import dev.sluide.ide.logging.currentLogFile
import javafx.event.ActionEvent
import javafx.geometry.Insets
import javafx.scene.control.ButtonBar.ButtonData
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.GridPane
import javafx.stage.Stage
import java.nio.file.Path

/**
 * What the About dialog and the top of a diagnostics report tell, as label and value; the parameters default to the
 * real values.
 */
internal fun aboutLines(
    version: String? = APP_VERSION,
    javaVendor: String? = System.getProperty("java.vendor"),
    javaVersion: String? = System.getProperty("java.runtime.version"),
    javaFxVersion: String? = System.getProperty("javafx.runtime.version"),
    osName: String? = System.getProperty("os.name"),
    osVersion: String? = System.getProperty("os.version"),
    osArch: String? = System.getProperty("os.arch"),
    settingsFolder: Path = Path.of(System.getProperty("user.home"), ".sluide"),
    logFile: Path = currentLogFile,
    diagnosticsFolder: Path = settingsFolder.resolve("diagnostics"),
): List<Pair<String, String>> =
    listOf(
        "Version" to (version ?: "unknown"),
        "Java" to "${javaVendor ?: "unknown"} ${javaVersion ?: "unknown"}",
        "JavaFX" to (javaFxVersion ?: "unknown"),
        "OS" to
            "${listOf(
                osName ?: "unknown",
                osVersion,
            ).filter { !it.isNullOrBlank() }.joinToString(" ")} (${osArch ?: "unknown"})",
        "Settings" to settingsFolder.toString(),
        "Log file" to logFile.toString(),
        "Diagnostics" to diagnosticsFolder.toString(),
    )

/** The [lines] as `key: value` lines, for the clipboard. */
internal fun aboutText(lines: List<Pair<String, String>>): String =
    lines.joinToString("\n") { (key, value) -> "$key: $value" }

/** Shows the About dialog over [owner] and waits until it is closed. */
internal fun showAboutDialog(
    owner: Stage,
    lines: List<Pair<String, String>>,
) {
    aboutDialog(owner, lines).showAndWait()
}

/** The About dialog, not shown: [lines] as selectable values, Copy (keeps the dialog open) and Close. */
internal fun aboutDialog(
    owner: Stage,
    lines: List<Pair<String, String>>,
): Dialog<ButtonType> {
    val copy = ButtonType("Copy", ButtonData.LEFT)
    val grid =
        GridPane().apply {
            hgap = 12.0
            vgap = 6.0
            padding = Insets(8.0)
            lines.forEachIndexed { row, (key, value) ->
                add(Label(key), 0, row)
                val field =
                    TextField(value).apply {
                        isEditable = false
                        style = "-fx-background-color: transparent; -fx-background-insets: 0; -fx-padding: 0;"
                        prefColumnCount = 40
                    }
                add(field, 1, row)
            }
        }
    return Dialog<ButtonType>().apply {
        initOwner(owner)
        title = "About Thoreau"
        headerText = appTitle()
        dialogPane.content = grid
        dialogPane.buttonTypes.addAll(copy, ButtonType.CLOSE)
        dialogPane.lookupButton(copy).addEventFilter(ActionEvent.ACTION) {
            Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(aboutText(lines)) })
            it.consume()
        }
    }
}
