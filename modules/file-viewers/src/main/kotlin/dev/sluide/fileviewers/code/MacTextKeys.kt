package dev.sluide.fileviewers.code

import dev.sluide.textediting.Selection
import dev.sluide.textediting.TextEdit
import javafx.event.Event
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.NavigationActions.SelectionPolicy
import org.fxmisc.wellbehaved.event.EventPattern
import org.fxmisc.wellbehaved.event.EventPattern.anyOf
import org.fxmisc.wellbehaved.event.EventPattern.keyPressed
import org.fxmisc.wellbehaved.event.InputMap
import org.fxmisc.wellbehaved.event.Nodes

/** Whether this runs on macOS. */
internal val onMac = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true)

/**
 * Adds [macTextKeys] to [area] if [mac] (macOS by default); other platforms keep RichTextFX's keys. RichTextFX binds
 * word skipping and word deletion to Shortcut (Cmd on macOS) and has no macOS variant, so without this a code area
 * on macOS does not behave like a Cocoa text view (ADR 0154).
 */
internal fun installMacTextKeys(
    area: CodeArea,
    mac: Boolean = onMac,
) {
    if (mac) Nodes.addInputMap(area, macTextKeys(area))
}

/**
 * The keys of a Cocoa text view, taking precedence over RichTextFX's own (add them with `Nodes.addInputMap`). The
 * navigation keys also extend the selection with Shift: Cmd+Left/Right go to the (visual) line start and end,
 * Option+Left/Right by word, Cmd+Up/Down to the start and end of the text; Option+Up/Down go to the start or end of
 * the paragraph (and to the previous or next one from there), without Shift, which moves lines ([editorKeys]).
 * Option+Backspace/Delete delete a word, Cmd+Backspace/Delete to the line start or end (one character when already
 * there); with a selection they delete just that. Deleting acts only while the area is editable, each as one undo
 * step. Cmd+Option+Left/Right (back and forward) are not matched.
 */
internal fun macTextKeys(area: CodeArea): InputMap<KeyEvent> {
    val meta = KeyCombination.META_DOWN
    val alt = KeyCombination.ALT_DOWN
    val anyShift = KeyCombination.SHIFT_ANY

    fun navigate(
        modifier: KeyCombination.Modifier,
        codes: List<KeyCode>,
        move: (SelectionPolicy) -> Unit,
    ): InputMap<KeyEvent> {
        val patterns: List<EventPattern<Event, KeyEvent>> = codes.map { keyPressed(it, modifier, anyShift) }
        val pattern: EventPattern<Event, KeyEvent> = anyOf(*patterns.toTypedArray())
        return InputMap.consume(pattern) { move(if (it.isShiftDown) SelectionPolicy.ADJUST else SelectionPolicy.CLEAR) }
    }

    val left = listOf(KeyCode.LEFT, KeyCode.KP_LEFT)
    val right = listOf(KeyCode.RIGHT, KeyCode.KP_RIGHT)
    val up = listOf(KeyCode.UP, KeyCode.KP_UP)
    val down = listOf(KeyCode.DOWN, KeyCode.KP_DOWN)
    return InputMap.sequence(
        navigate(meta, left) { area.lineStart(it) },
        navigate(meta, right) { area.lineEnd(it) },
        navigate(alt, left) { area.wordLeft(it) },
        navigate(alt, right) { area.wordRight(it) },
        navigate(meta, up) { area.start(it) },
        navigate(meta, down) { area.end(it) },
        InputMap.consume(anyOf(*up.map { keyPressed(it, alt) }.toTypedArray())) {
            area.moveTo(paragraphTarget(area.text, area.caretPosition, forward = false))
        },
        InputMap.consume(anyOf(*down.map { keyPressed(it, alt) }.toTypedArray())) {
            area.moveTo(paragraphTarget(area.text, area.caretPosition, forward = true))
        },
        deleteWithTarget(area, alt, KeyCode.BACK_SPACE, forward = false) { area.wordLeft(SelectionPolicy.CLEAR) },
        deleteWithTarget(area, alt, KeyCode.DELETE, forward = true) { area.wordRight(SelectionPolicy.CLEAR) },
        deleteWithTarget(area, meta, KeyCode.BACK_SPACE, forward = false) { area.lineStart(SelectionPolicy.CLEAR) },
        deleteWithTarget(area, meta, KeyCode.DELETE, forward = true) { area.lineEnd(SelectionPolicy.CLEAR) },
    )
}

private fun deleteWithTarget(
    area: CodeArea,
    modifier: KeyCombination.Modifier,
    code: KeyCode,
    forward: Boolean,
    moveToTarget: () -> Unit,
): InputMap<KeyEvent> {
    val pattern: EventPattern<Event, KeyEvent> = keyPressed(code, modifier)
    return InputMap.consumeWhen(pattern, { area.isEditable }) {
        val selection = Selection(area.anchor, area.caretPosition)
        // Moving an empty selection's caret to the target changes nothing visible; the edit then sets the caret.
        if (selection.isEmpty) moveToTarget()
        area.applyEdit(macDeleteEdit(selection, area.caretPosition, forward, area.length))
    }
}

private fun CodeArea.wordLeft(policy: SelectionPolicy) = moveTo(macWordLeft(text, caretPosition), policy)

private fun CodeArea.wordRight(policy: SelectionPolicy) = moveTo(macWordRight(text, caretPosition), policy)

private fun isWordChar(c: Char) = Character.isLetterOrDigit(c) || c == '_'

/** Where Option+Right takes a caret at [caret] in [text]: past the non-word characters, then to the end of the word. */
internal fun macWordRight(
    text: String,
    caret: Int,
): Int {
    var i = caret
    while (i < text.length && !isWordChar(text[i])) i++
    while (i < text.length && isWordChar(text[i])) i++
    return i
}

/** Where Option+Left takes a caret at [caret] in [text]: back past non-word characters, then to the word's start. */
internal fun macWordLeft(
    text: String,
    caret: Int,
): Int {
    var i = caret
    while (i > 0 && !isWordChar(text[i - 1])) i--
    while (i > 0 && isWordChar(text[i - 1])) i--
    return i
}

/**
 * Where Option+Up (not [forward]) or Option+Down takes a caret at [caret] in [text]: the start of its paragraph, or
 * of the previous one when already there; the end of its paragraph, or of the next one when already there.
 */
internal fun paragraphTarget(
    text: String,
    caret: Int,
    forward: Boolean,
): Int {
    if (!forward) {
        val start = text.lastIndexOf('\n', caret - 1) + 1
        if (caret > start) return start
        return if (start == 0) 0 else text.lastIndexOf('\n', start - 2) + 1
    }
    val end = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
    if (caret < end) return end
    return if (end == text.length) end else text.indexOf('\n', end + 1).let { if (it < 0) text.length else it }
}

/**
 * The deletion of a Cocoa delete key: just [selection] when something is selected; otherwise from the caret to [target]
 * (where the key's move would go), or, when that is the caret itself (already at the line start or end), the one
 * character before it (not [forward]) or after it; `null` at the start or end of a text of [length].
 */
internal fun macDeleteEdit(
    selection: Selection,
    target: Int,
    forward: Boolean,
    length: Int,
): TextEdit? {
    val (start, end) =
        when {
            !selection.isEmpty -> selection.start to selection.end
            target != selection.caret -> minOf(target, selection.caret) to maxOf(target, selection.caret)
            forward -> selection.caret to minOf(selection.caret + 1, length)
            else -> maxOf(selection.caret - 1, 0) to selection.caret
        }
    return if (start == end) null else TextEdit(start, end, "", Selection(start))
}
