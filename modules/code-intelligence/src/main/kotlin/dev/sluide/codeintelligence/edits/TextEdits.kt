package dev.sluide.codeintelligence.edits

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextEdit

/** A replacement of the text's [start, end) by [text]; UTF-16 offsets. */
internal data class TextReplacement(
    val start: Int,
    val end: Int,
    val text: String,
)

/** The LSP text edits of one file checked against its text: the replacements, or why they cannot be applied. */
internal sealed interface CheckedEdits {
    data class Valid(
        val replacements: List<TextReplacement>,
    ) : CheckedEdits

    data class Invalid(
        val reason: String,
    ) : CheckedEdits
}

/**
 * The lines of a text whose lines end with `\r\n`, `\r` or `\n` (an editor's text has `\n` only, a file on disk keeps
 * its own), found once, so that the many positions of a rename convert without scanning the text for each.
 */
internal class TextLines(
    private val text: CharSequence,
) {
    /** The start of each line. */
    private val starts: IntArray

    /** The end of each line: where its line break starts, or the end of the text. */
    private val ends: IntArray

    init {
        var startList = IntArray(16)
        var endList = IntArray(16)
        var count = 0
        var lineStart = 0
        var i = 0
        while (true) {
            while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
            if (count == startList.size) {
                startList = startList.copyOf(count * 2)
                endList = endList.copyOf(count * 2)
            }
            startList[count] = lineStart
            endList[count] = i
            count++
            if (i == text.length) break
            i += if (text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') 2 else 1
            lineStart = i
        }
        starts = startList.copyOf(count)
        ends = endList.copyOf(count)
    }

    /**
     * The offset of [position]: lines from 0, characters in UTF-16 units. A character past the end of its line is the
     * line's end (LSP), the line after the last one is the end of the text; `null` for a negative position or a line
     * further out.
     */
    fun offsetOf(position: Position): Int? {
        val line = position.line
        if (line < 0 || position.character < 0) return null
        if (line == starts.size) return text.length
        if (line > starts.size) return null
        return (starts[line] + position.character).coerceAtMost(ends[line])
    }

    /** Whether [offset] lies between the two halves of a character outside the Basic Multilingual Plane. */
    fun splitsCharacter(offset: Int): Boolean =
        offset in 1 until text.length && text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()
}

/** The offset of [position] in [text], as [TextLines.offsetOf], for a single position. */
internal fun offsetIn(
    text: CharSequence,
    position: Position,
): Int? = TextLines(text).offsetOf(position)

/**
 * [edits] of [text] as replacements, sorted by their range (inserts at one offset in the order given), with their new
 * texts' line breaks as [lineEnding] if given; [CheckedEdits.Invalid] for a range outside the text, ending before it
 * starts or splitting a character, and for overlapping edits. Nothing is applied: every file of a workspace edit is
 * checked before any changes.
 */
internal fun checkEdits(
    text: CharSequence,
    edits: List<TextEdit>,
    lineEnding: String? = null,
): CheckedEdits {
    val lines = TextLines(text)
    val replacements = mutableListOf<TextReplacement>()
    for (edit in edits) {
        val range = edit.range ?: return CheckedEdits.Invalid("an edit without a range")
        val start = range.start?.let(lines::offsetOf)
        val end = range.end?.let(lines::offsetOf)
        if (start == null || end == null) return CheckedEdits.Invalid("an edit outside the text")
        if (end < start) return CheckedEdits.Invalid("an edit that ends before it starts")
        if (lines.splitsCharacter(start) || lines.splitsCharacter(end)) {
            return CheckedEdits.Invalid("an edit that splits a character")
        }
        val newText = edit.newText.orEmpty()
        replacements +=
            TextReplacement(start, end, if (lineEnding == null) newText else withLineEnding(newText, lineEnding))
    }
    val sorted = replacements.sortedWith(compareBy({ it.start }, { it.end }))
    if (sorted.zipWithNext().any { (previous, next) -> previous.end > next.start }) {
        return CheckedEdits.Invalid("overlapping edits")
    }
    return CheckedEdits.Valid(sorted)
}

/** [text] with [sorted] replacements ([checkEdits]) applied from the last, so that every offset stays valid. */
internal fun applyReplacements(
    text: String,
    sorted: List<TextReplacement>,
): String {
    val result = StringBuilder(text)
    for (replacement in sorted.asReversed()) result.replace(replacement.start, replacement.end, replacement.text)
    return result.toString()
}

/** The line break [text] uses: its first one, `\n` without any. */
internal fun lineEndingOf(text: CharSequence): String {
    val at = text.indexOfFirst { it == '\n' || it == '\r' }
    return when {
        at < 0 -> "\n"
        text[at] == '\n' -> "\n"
        at + 1 < text.length && text[at + 1] == '\n' -> "\r\n"
        else -> "\r"
    }
}

/** [text] with every line break (`\r\n`, `\r`, `\n`) as [lineEnding]. */
internal fun withLineEnding(
    text: String,
    lineEnding: String,
): String {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    return if (lineEnding == "\n") normalized else normalized.replace("\n", lineEnding)
}
