package dev.sluide.fileviewers.code

import dev.sluide.textediting.CommentSyntax
import dev.sluide.textediting.IndentStyle
import dev.sluide.textediting.Indentation
import dev.sluide.textediting.LineComments
import dev.sluide.textediting.LineOperations
import dev.sluide.textediting.Selection
import dev.sluide.textediting.TextEdit
import javafx.event.Event
import javafx.scene.input.KeyCharacterCombination
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import org.fxmisc.richtext.CodeArea
import org.fxmisc.wellbehaved.event.EventPattern
import org.fxmisc.wellbehaved.event.EventPattern.anyOf
import org.fxmisc.wellbehaved.event.EventPattern.keyPressed
import org.fxmisc.wellbehaved.event.InputMap

/**
 * The editing keys of a code view's [area], taking precedence over RichTextFX's own (add them with
 * `Nodes.addInputMap`): Enter (also with Shift) keeps the indentation, Tab and Shift+Tab indent and outdent in
 * the current [style]; Shortcut+D duplicates the selection or line, Shortcut+Y deletes lines, Alt+Shift+Up/Down move them;
 * Shortcut+/ ([TOGGLE_COMMENT]) and Shortcut+Shift+7 ([TOGGLE_COMMENT_KEY]) comment lines out with [comment] or back
 * in. They act only while the area is editable; otherwise RichTextFX's bindings, which do nothing then either, get
 * the keys.
 */
internal fun editorKeys(
    area: CodeArea,
    style: () -> IndentStyle,
    comment: CommentSyntax,
): InputMap<KeyEvent> =
    InputMap.sequence(
        area.editOn(keyPressed(KeyCode.ENTER, KeyCombination.SHIFT_ANY), typing = true) { text, selection ->
            Indentation.newline(text, selection, style())
        },
        area.editOn(keyPressed(KeyCode.TAB), typing = true) { text, selection ->
            Indentation.indent(text, selection, style())
        },
        area.editOn(keyPressed(KeyCode.TAB, KeyCombination.SHIFT_DOWN)) { text, selection ->
            Indentation.outdent(text, selection, style())
        },
        area.editOn(keyPressed(KeyCode.D, KeyCombination.SHORTCUT_DOWN), edit = LineOperations::duplicate),
        area.editOn(
            keyPressed(KeyCode.Y, KeyCombination.SHORTCUT_DOWN),
            edit = LineOperations::deleteLines,
        ),
        area.editOn(
            keyPressed(KeyCode.UP, KeyCombination.ALT_DOWN, KeyCombination.SHIFT_DOWN),
            edit = LineOperations::moveUp,
        ),
        area.editOn(
            keyPressed(KeyCode.DOWN, KeyCombination.ALT_DOWN, KeyCombination.SHIFT_DOWN),
            edit = LineOperations::moveDown,
        ),
        area.editOn(anyOf(keyPressed(TOGGLE_COMMENT), keyPressed(TOGGLE_COMMENT_KEY))) { text, selection ->
            LineComments.toggle(text, selection, comment)
        },
    )

/**
 * Shortcut+/, matched by the character rather than the key, with or without Shift: on many layouts (e.g. German or
 * Swedish) `/` is Shift+7.
 */
internal val TOGGLE_COMMENT = KeyCharacterCombination("/", KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_ANY)

/**
 * Shortcut+Shift+7, matched by the key: where `/` is Shift+7 the same keys as [TOGGLE_COMMENT], but without asking the
 * platform which key types `/`. On macOS JavaFX only knows that after the key has been pressed for real, so synthetic
 * key events (UI tests) can never match [TOGGLE_COMMENT] there.
 */
internal val TOGGLE_COMMENT_KEY =
    KeyCodeCombination(KeyCode.DIGIT7, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)

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

/**
 * Applies [edits] (sorted, not overlapping, offsets in the current text) as one multi-change, so that they are one
 * undo step of their own and the text between them stays untouched, then selects [selection]. Does nothing without
 * edits.
 */
internal fun CodeArea.applyEdits(
    edits: List<TextEdit>,
    selection: Selection,
) {
    if (edits.isEmpty()) return
    undoManager.preventMerge()
    val change = createMultiChange(edits.size)
    // From the end, so that each offset is still the same in the text the earlier changes left.
    for (edit in edits.asReversed()) change.replaceTextAbsolutely(edit.start, edit.end, edit.text)
    change.commit()
    undoManager.preventMerge()
    selectRange(selection.anchor, selection.caret)
    requestFollowCaret()
}
