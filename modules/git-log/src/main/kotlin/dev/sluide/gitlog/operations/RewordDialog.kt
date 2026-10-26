package dev.sluide.gitlog.operations

import dev.sluide.gitlog.data.SHORT_HASH
import dev.sluide.gitlog.ui.GIT_LOG_CSS
import javafx.application.Platform
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.stage.Window

internal val CHANGE_MESSAGE_BUTTON = ButtonType("Change Message", ButtonBar.ButtonData.OK_DONE)

private val CHANGE = KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN)

/**
 * Asks for the new message of a commit (ADR 0100): the message of the [prompt] in an area, the notes below it, Change
 * Message (while the text is a new one) or Cancel. Enter is a newline; Shortcut+Enter changes the message. The result
 * is the edited text.
 */
internal class RewordDialog(
    owner: Window?,
    private val prompt: RewordPrompt,
) : Dialog<String>() {
    val area =
        TextArea(prompt.message).apply {
            styleClass += "git-commit-message"
            font = Font.font(MONOSPACED, Font.getDefault().size)
            isWrapText = false
            prefRowCount = ROWS
            prefColumnCount = COLUMNS
            // The dialog would otherwise squeeze it to one line.
            minHeight = Region.USE_PREF_SIZE
        }

    init {
        owner?.let(::initOwner)
        title = "Edit Commit Message"
        isResizable = true
        headerText = "Message of ${prompt.commit.take(SHORT_HASH)} on ${prompt.branch}"
        dialogPane.stylesheets += GIT_LOG_CSS
        dialogPane.buttonTypes.setAll(CHANGE_MESSAGE_BUTTON, ButtonType.CANCEL)
        val notes =
            rewordNotes(prompt.otherBranches, prompt.pushedTo).map {
                Label(it).apply {
                    styleClass += "git-commit-note"
                    isWrapText = true
                }
            }
        dialogPane.content = VBox(SPACING, area, *notes.toTypedArray()).apply { VBox.setVgrow(area, Priority.ALWAYS) }
        val change = dialogPane.lookupButton(CHANGE_MESSAGE_BUTTON) as Button
        // Enter stays a newline in the area.
        change.isDefaultButton = false
        (dialogPane.lookupButton(ButtonType.CANCEL) as Button).isDefaultButton = false
        change.disableProperty().bind(
            area.textProperty().map { !canChangeMessage(prompt.message, it) },
        )
        area.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (CHANGE.match(event)) {
                if (!change.isDisabled) change.fire()
                event.consume()
            }
        }
        setResultConverter { button -> if (button == CHANGE_MESSAGE_BUTTON) area.text else null }
        setOnShown {
            Platform.runLater {
                area.requestFocus()
                area.positionCaret(area.text.length)
            }
        }
    }
}

private const val SPACING = 8.0
private const val ROWS = 10
private const val COLUMNS = 72
