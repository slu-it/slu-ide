package dev.sluide.pdf.view

import dev.sluide.pdf.engine.PageRect
import dev.sluide.pdf.engine.PageText
import kotlin.math.max
import kotlin.math.min

/**
 * The rects that cover the chars [start] until [end] of [text] (points of the page as displayed): one union rect per
 * line run. Chars without a box (spaces and line ends the extraction inserted) are skipped without ending a run. A run
 * ends at a `\n`, and where the next box shares less than half of the smaller extent with the previous box on the x
 * axis and on the y axis alike: a line running across shares its height, one running down a turned page its width.
 * An empty range, or one without boxes, gives none.
 */
internal fun textRects(
    text: PageText,
    start: Int,
    end: Int,
): List<PageRect> {
    val rects = mutableListOf<PageRect>()
    var run: Run? = null
    for (index in max(start, 0) until min(end, text.text.length)) {
        if (text.text[index] == '\n') {
            run?.let { rects += it.rect }
            run = null
            continue
        }
        val box = text.boxes[index] ?: continue
        val current = run
        run =
            if (current != null && sameLine(current.last, box)) {
                Run(union(current.rect, box), box)
            } else {
                current?.let { rects += it.rect }
                Run(box, box)
            }
    }
    run?.let { rects += it.rect }
    return rects
}

/** A line run: the union of its boxes, and the box of its last char. */
private class Run(
    val rect: PageRect,
    val last: PageRect,
)

/** Whether [next] continues the line of [previous]: it shares half the smaller extent on the x or the y axis. */
internal fun sameLine(
    previous: PageRect,
    next: PageRect,
): Boolean =
    shares(previous.x, previous.width, next.x, next.width) || shares(previous.y, previous.height, next.y, next.height)

private fun shares(
    start: Double,
    size: Double,
    otherStart: Double,
    otherSize: Double,
): Boolean = min(start + size, otherStart + otherSize) - max(start, otherStart) >= min(size, otherSize) / 2

private fun union(
    a: PageRect,
    b: PageRect,
): PageRect {
    val left = min(a.x, b.x)
    val top = min(a.y, b.y)
    return PageRect(left, top, max(a.x + a.width, b.x + b.width) - left, max(a.y + a.height, b.y + b.height) - top)
}
