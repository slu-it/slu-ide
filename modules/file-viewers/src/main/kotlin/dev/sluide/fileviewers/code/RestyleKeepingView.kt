package dev.sluide.fileviewers.code

import org.fxmisc.flowless.VirtualFlow
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.model.StyleSpans

/**
 * Sets the styles from [from] on to [spans] like `setStyleSpans`, without moving the shown lines. RichTextFX replaces
 * the paragraphs of the range and Flowless drops their cells; a follow-caret request pending in that pulse then meets no
 * cell and shows the caret paragraph at the top; without a request the view does not keep its place either, as
 * `VirtualizedScrollPane`'s vertical scroll bar falls back to 0 once no measured cell is left. So in a scene the area
 * is laid out before (outside the pulse, with the area's current size), which runs a pending request while the cells
 * exist; then the first shown paragraph is put back at its exact offset; then the area is laid out again, which creates
 * the cells for a request later in the same pulse. Without cells or a scene it is a plain `setStyleSpans`. FX thread.
 */
internal fun CodeArea.restyleKeepingView(
    from: Int,
    spans: StyleSpans<Collection<String>>,
) {
    val flow = if (scene == null) null else virtualFlow()
    if (flow == null) {
        setStyleSpans(from, spans)
        return
    }
    layout()
    val first = firstVisibleParToAllParIndex()
    val cell = if (first < 0) null else flow.getCellIfVisible(first).orElse(null)
    if (cell == null) {
        setStyleSpans(from, spans)
        return
    }
    val offset = cell.node.layoutY + cell.node.layoutBounds.minY
    setStyleSpans(from, spans)
    flow.showAtOffset(first, offset)
    layout()
}

/** The [VirtualFlow] that lays out the paragraphs of this area; `null` before its skin exists. */
internal fun CodeArea.virtualFlow(): VirtualFlow<*, *>? =
    childrenUnmodifiable.firstNotNullOfOrNull { it as? VirtualFlow<*, *> }
