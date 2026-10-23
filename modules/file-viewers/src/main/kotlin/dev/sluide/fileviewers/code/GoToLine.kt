package dev.sluide.fileviewers.code

import dev.sluide.textediting.parseGoTo
import javafx.scene.control.TextInputDialog
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import org.fxmisc.richtext.CodeArea
import org.fxmisc.wellbehaved.event.EventPattern.keyPressed
import org.fxmisc.wellbehaved.event.InputMap

/**
 * Go to a line of a code view's [area]: Shortcut+G ([keys]) [asks][ask] for `line[:column]` (1-based), offering the
 * caret's position, and moves the caret there, scrolling it into view. Lines and columns beyond the text go to its
 * last line and the line's end; other input does nothing.
 */
internal class GoToLine(
    private val area: CodeArea,
) {
    /** Asks for the position to go to, given the caret's; `null` if cancelled. Tests replace it, since it blocks. */
    var ask: (current: String) -> String? = ::showDialog

    val keys: InputMap<KeyEvent> =
        InputMap.consume(keyPressed(KeyCode.G, KeyCombination.SHORTCUT_DOWN)) { goToLine() }

    fun goToLine() {
        val input = ask("${area.currentParagraph + 1}:${area.caretColumn + 1}") ?: return
        val position = parseGoTo(input, area.paragraphs.size) ?: return
        moveTo(position.offsetIn(area.text))
    }

    /** Goes to the start of [line] (0-based, kept within the text) without asking. */
    fun goTo(line: Int) = moveTo(area.getAbsolutePosition(line.coerceIn(0, area.paragraphs.size - 1), 0))

    private fun moveTo(offset: Int) {
        area.moveTo(offset)
        area.requestFollowCaret()
        area.requestFocus()
    }

    private fun showDialog(current: String): String? =
        TextInputDialog(current)
            .apply {
                area.scene?.window?.let(::initOwner)
                title = "Go to Line"
                headerText = null
                contentText = "Line[:Column]:"
            }.showAndWait()
            .orElse(null)
}
