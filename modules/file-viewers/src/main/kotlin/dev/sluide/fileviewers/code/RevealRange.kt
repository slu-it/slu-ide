package dev.sluide.fileviewers.code

import javafx.geometry.Bounds
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.model.TwoDimensional.Bias

/**
 * Scrolls [start, end) (kept within the text) into view; caret, selection and focus stay. The start is always shown;
 * the end too when both fit the viewport together. Used by [TextAreaEditor.reveal] and by the code views' extensions.
 */
internal fun CodeArea.revealRange(
    start: Int,
    end: Int,
) {
    if (length == 0) return
    val from = start.coerceIn(0, length)
    val to = end.coerceIn(from, length)
    showParagraphInViewport(offsetToPosition(from, Bias.Forward).major)
    layout()
    val first = charBounds(from) ?: return
    if (first.minY < 0) {
        scrollYBy(first.minY)
    } else if (first.maxY > height) {
        scrollYBy(first.maxY - height)
    }
    // The last character that shows: a line break has no bounds.
    var lastChar = to - 1
    while (lastChar > from && text[lastChar] == '\n') lastChar--
    if (lastChar <= from) return
    layout()
    // The end's paragraph is not laid out while it is far below: put the start at the top, where the end shows if
    // anything shows it.
    val last =
        charBounds(lastChar) ?: run {
            showParagraphAtTop(offsetToPosition(from, Bias.Forward).major)
            layout()
            return
        }
    val top = charBounds(from) ?: return
    // As far down as the end needs, but never past where the start leaves the top.
    if (last.maxY > height) scrollYBy(minOf(last.maxY - height, maxOf(top.minY, 0.0)))
}

/** The bounds of the character at [offset] (the last one at the end) in the area's coordinates, if laid out. */
private fun CodeArea.charBounds(offset: Int): Bounds? {
    val at = offset.coerceIn(0, length - 1)
    return getCharacterBoundsOnScreen(at, at + 1).orElse(null)?.let { screenToLocal(it) }
}
