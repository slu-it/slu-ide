package dev.sluide.pdf.view

import dev.sluide.pdf.engine.PageRect
import dev.sluide.pdf.engine.PageText
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A char of the document: its page and its index in that page's text. */
internal data class TextPosition(
    val page: Int,
    val index: Int,
) : Comparable<TextPosition> {
    override fun compareTo(other: TextPosition): Int = compareValuesBy(this, other, { it.page }, { it.index })
}

/**
 * A selected range of the document's text, kept in text offsets so that it survives scrolling and zoom: from the char
 * ([startPage], [startIndex]) to the char ([endPage], [endIndex]), both included, in document order. A page's text is
 * the one of `PageText.text`; an [endIndex] beyond a page's length reaches its end.
 */
internal data class TextSelection(
    val startPage: Int,
    val startIndex: Int,
    val endPage: Int,
    val endIndex: Int,
) {
    /** The chars of [page] (whose text is [length] long) that are selected; `null` if none. */
    fun rangeOn(
        page: Int,
        length: Int,
    ): IntRange? {
        if (page < startPage || page > endPage) return null
        val first = if (page == startPage) startIndex else 0
        val last = if (page == endPage) min(endIndex, length - 1) else length - 1
        return if (first <= last) first..last else null
    }

    /**
     * The selected text, [texts] giving the text of a page: the pages joined by blank lines, the spaces at the ends of
     * lines and the line ends at the end of a page trimmed.
     */
    fun copyText(texts: (Int) -> String): String =
        (startPage..endPage)
            .mapNotNull { page ->
                val text = texts(page)
                rangeOn(page, text.length)?.let { text.substring(it.first, it.last + 1) }
            }.map { part -> part.lines().joinToString("\n") { it.trimEnd(' ') }.trimEnd('\n') }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")

    companion object {
        /** The range from [a] to [b], whichever comes first. */
        fun between(
            a: TextPosition,
            b: TextPosition,
        ): TextSelection {
            val first = minOf(a, b)
            val last = maxOf(a, b)
            return TextSelection(first.page, first.index, last.page, last.index)
        }

        /** Every page's whole text, of pages 0 to [lastPage]. */
        fun all(lastPage: Int): TextSelection = TextSelection(0, 0, lastPage, Int.MAX_VALUE)

        /**
         * The char of [text] closest to the point ([x], [y]): the closest box on the closest line, a line being as
         * [textRects] sees it; `null` without boxes. Lines are compared across their direction, so a point beyond the
         * end of a short line still belongs to it.
         */
        fun charAt(
            text: PageText,
            x: Double,
            y: Double,
        ): Int? {
            val lines = lines(text)
            if (lines.isEmpty()) return null
            val horizontalPage = lines.count { it.horizontal == true } >= lines.count { it.horizontal == false }
            val line =
                lines.minWith(
                    compareBy<Line>(
                        { outside(it.rect, x, y, it.horizontal ?: horizontalPage) },
                        { distance(it.rect, x, y) },
                    ),
                )
            return line.chars.minBy { distance(checkNotNull(text.boxes[it]), x, y) }
        }

        /** The char whose box contains the point ([x], [y]); `null` if none does. */
        fun charUnder(
            text: PageText,
            x: Double,
            y: Double,
        ): Int? =
            text.boxes.indices.firstOrNull { index ->
                text.boxes[index]?.let { x >= it.x && x <= it.x + it.width && y >= it.y && y <= it.y + it.height } ==
                    true
            }

        /** The word around the char [index]: letters, digits, `_` and `'`; any other char is a word of its own. */
        fun wordAt(
            text: PageText,
            index: Int,
        ): IntRange {
            if (!isWordChar(text.text[index])) return index..index
            var first = index
            while (first > 0 && isWordChar(text.text[first - 1])) first--
            var last = index
            while (last < text.text.length - 1 && isWordChar(text.text[last + 1])) last++
            return first..last
        }

        private fun isWordChar(char: Char) = char.isLetterOrDigit() || char == '_' || char == '\''

        /** A line: the indices of its boxed chars, the rect around them, and whether it runs along x (`null`: unknown). */
        private class Line(
            val chars: List<Int>,
            val rect: PageRect,
            val horizontal: Boolean?,
        )

        private fun lines(text: PageText): List<Line> {
            val runs = mutableListOf<MutableList<Int>>()
            var previous: PageRect? = null
            for (index in text.text.indices) {
                if (text.text[index] == '\n') {
                    previous = null
                    continue
                }
                val box = text.boxes[index] ?: continue
                if (previous != null && sameLine(previous, box)) runs.last() += index else runs += mutableListOf(index)
                previous = box
            }
            return runs.map { run ->
                val first = checkNotNull(text.boxes[run.first()])
                val last = checkNotNull(text.boxes[run.last()])
                val horizontal = if (run.size < 2) null else abs(last.x - first.x) >= abs(last.y - first.y)
                Line(run, run.map { checkNotNull(text.boxes[it]) }.reduce(::unionOf), horizontal)
            }
        }

        private fun unionOf(
            a: PageRect,
            b: PageRect,
        ): PageRect {
            val left = min(a.x, b.x)
            val top = min(a.y, b.y)
            return PageRect(
                left,
                top,
                max(a.x + a.width, b.x + b.width) - left,
                max(a.y + a.height, b.y + b.height) - top,
            )
        }

        /** How far the point is from [rect] across its line's direction: above or below a line running along x. */
        private fun outside(
            rect: PageRect,
            x: Double,
            y: Double,
            horizontal: Boolean,
        ): Double = if (horizontal) outside(y, rect.y, rect.height) else outside(x, rect.x, rect.width)

        private fun distance(
            rect: PageRect,
            x: Double,
            y: Double,
        ): Double = outside(x, rect.x, rect.width).let { it * it } + outside(y, rect.y, rect.height).let { it * it }

        private fun outside(
            point: Double,
            start: Double,
            size: Double,
        ): Double = max(max(start - point, point - (start + size)), 0.0)
    }
}
