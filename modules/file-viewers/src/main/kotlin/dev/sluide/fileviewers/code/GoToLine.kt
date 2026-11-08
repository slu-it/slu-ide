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
 * caret's position, and moves the caret there; the line becomes the first shown one unless it is fully in view
 * ([JumpScroll], which the view shares with its extensions' jumps). Lines and columns beyond the text go to its last
 * line and the line's end; other input does nothing.
 */
internal class GoToLine(
    private val area: CodeArea,
    private val jumpScroll: JumpScroll,
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

    /** Goes to [column] of [line] (0-based, both kept within the text; the line's start by default) without asking. */
    fun goTo(
        line: Int,
        column: Int = 0,
    ) = moveTo(area.offsetOf(line, column))

    private fun moveTo(offset: Int) {
        jumpScroll.jump(offset)
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

/**
 * The offset of [column] in [line] (0-based): the line kept within the text, the column within the line, UTF-16 units.
 */
internal fun CodeArea.offsetOf(
    line: Int,
    column: Int,
): Int {
    val paragraph = line.coerceIn(0, paragraphs.size - 1)
    return getAbsolutePosition(paragraph, column.coerceIn(0, getParagraphLength(paragraph)))
}
