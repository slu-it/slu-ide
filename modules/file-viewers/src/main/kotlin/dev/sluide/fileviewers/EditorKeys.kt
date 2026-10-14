package dev.sluide.fileviewers

import dev.sluide.textediting.IndentStyle
import dev.sluide.textediting.Indentation
import dev.sluide.textediting.Selection
import dev.sluide.textediting.TextEdit
import javafx.event.Event
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import org.fxmisc.richtext.CodeArea
import org.fxmisc.wellbehaved.event.EventPattern
import org.fxmisc.wellbehaved.event.EventPattern.keyPressed
import org.fxmisc.wellbehaved.event.InputMap

/**
 * The editing keys of a code view's [area], taking precedence over RichTextFX's own (add them with
 * `Nodes.addInputMap`): Enter (also with Shift) keeps the indentation, Tab and Shift+Tab indent and outdent in
 * [style]. They act only while the area is editable; otherwise RichTextFX's bindings, which do nothing then either,
 * get the keys.
 */
internal fun editorKeys(
    area: CodeArea,
    style: IndentStyle,
): InputMap<KeyEvent> =
    InputMap.sequence(
        area.editOn(keyPressed(KeyCode.ENTER, KeyCombination.SHIFT_ANY), typing = true) { text, selection ->
            Indentation.newline(text, selection, style)
        },
        area.editOn(keyPressed(KeyCode.TAB), typing = true) { text, selection ->
            Indentation.indent(text, selection, style)
        },
        area.editOn(keyPressed(KeyCode.TAB, KeyCombination.SHIFT_DOWN)) { text, selection ->
            Indentation.outdent(text, selection, style)
        },
    )

/**
 * Consumes key presses matching [pattern] while the area is editable, applying the edit that [edit] makes of the
 * text and selection ([applyEdit]). The key is consumed also when there is nothing to change. A key that is [typing]
 * (Enter, Tab) merges with the typing around it as long as it leaves just a caret; Tab over several lines keeps them
 * selected and is an undo step of its own.
 */
private fun CodeArea.editOn(
    pattern: EventPattern<Event, KeyEvent>,
    typing: Boolean = false,
    edit: (text: String, selection: Selection) -> TextEdit?,
): InputMap<KeyEvent> =
    InputMap.consumeWhen(pattern, { isEditable }) {
        val change = edit(text, Selection(anchor, caretPosition))
        applyEdit(change, typing && change?.selection?.isEmpty == true)
    }

/**
 * Applies [edit] with one replacement, so that it is one undo step, and selects its selection; does nothing for
 * `null`. An edit that is [typing] (Enter, Tab) may be merged with the typing around it into one undo step; any other
 * is an undo step of its own.
 */
internal fun CodeArea.applyEdit(
    edit: TextEdit?,
    typing: Boolean = false,
) {
    if (edit == null) return
    if (!typing) undoManager.preventMerge()
    replaceText(edit.start, edit.end, edit.text)
    if (!typing) undoManager.preventMerge()
    selectRange(edit.selection.anchor, edit.selection.caret)
    requestFollowCaret()
}
