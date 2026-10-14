package dev.sluide.textediting

/** The offset where the line containing [offset] starts. */
internal fun lineStart(
    text: String,
    offset: Int,
): Int = text.lastIndexOf('\n', offset - 1) + 1

/** The offset where the line containing [offset] ends, before its `\n`. */
internal fun lineEnd(
    text: String,
    offset: Int,
): Int = text.indexOf('\n', offset).let { if (it < 0) text.length else it }

/** Whole lines of a text, from the [start] of the first to the [end] of the last (exclusive, before its `\n`). */
internal data class LineBlock(
    val start: Int,
    val end: Int,
) {
    /** The start offsets of the lines in this block. */
    fun lineStarts(text: String): List<Int> =
        buildList {
            var lineStart = start
            while (true) {
                add(lineStart)
                val lineEnd = lineEnd(text, lineStart)
                if (lineEnd >= end) break
                lineStart = lineEnd + 1
            }
        }
}

/** The lines a selection touches. A selection ending at the start of a line does not touch that line. */
internal fun touchedLines(
    text: String,
    selection: Selection,
): LineBlock {
    val first = lineStart(text, selection.start)
    val lastOffset =
        if (!selection.isEmpty && selection.end > first && lineStart(text, selection.end) == selection.end) {
            selection.end - 1
        } else {
            selection.end
        }
    return LineBlock(first, lineEnd(text, lastOffset))
}

/** Replaces [length] characters at [offset] by [text]. */
internal data class Splice(
    val offset: Int,
    val length: Int,
    val text: String,
)

/**
 * One edit that applies [splices] (sorted, not overlapping) to [text] and moves [selection] along. An offset at or
 * before a splice stays; one after it moves by the change in length, one inside removed text moves to the end of the
 * inserted text.
 * `null` if there is nothing to change.
 */
internal fun spliceEdit(
    text: String,
    splices: List<Splice>,
    selection: Selection,
): TextEdit? {
    val changes = splices.filter { it.length > 0 || it.text.isNotEmpty() }
    if (changes.isEmpty()) return null
    val start = changes.first().offset
    val end = changes.last().let { it.offset + it.length }
    val replacement =
        buildString {
            var position = start
            for (splice in changes) {
                append(text, position, splice.offset)
                append(splice.text)
                position = splice.offset + splice.length
            }
        }

    fun map(offset: Int): Int {
        var delta = 0
        for (splice in changes) {
            when {
                offset <= splice.offset -> return offset + delta
                offset < splice.offset + splice.length -> return splice.offset + delta + splice.text.length
                else -> delta += splice.text.length - splice.length
            }
        }
        return offset + delta
    }
    return TextEdit(start, end, replacement, Selection(map(selection.anchor), map(selection.caret)))
}
