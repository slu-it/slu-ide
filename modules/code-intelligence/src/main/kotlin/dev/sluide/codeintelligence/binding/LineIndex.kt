package dev.sluide.codeintelligence.binding

import org.eclipse.lsp4j.Position

/**
 * The line starts of a text with `\n` line breaks (the editor's, the document's copy), to convert many LSP positions
 * at once (diagnostics, semantic tokens) without scanning the text for each. Positions clamp as
 * [DocumentBinding.offsetOf] does: a line past the end is the end, a character past its line the line's end.
 */
internal class LineIndex(
    private val text: CharSequence,
) {
    private val starts: IntArray =
        run {
            var found = IntArray(64)
            var count = 1
            for (i in text.indices) {
                if (text[i] != '\n') continue
                if (count == found.size) found = found.copyOf(count * 2)
                found[count++] = i + 1
            }
            found.copyOf(count)
        }

    /** The number of lines, at least 1. */
    val lineCount: Int get() = starts.size

    /** The offset of [line] (0-based) and UTF-16 [character], clamped to the line and the text. */
    fun offsetOf(
        line: Int,
        character: Int,
    ): Int {
        if (line < 0) return 0
        if (line >= starts.size) return text.length
        val end = if (line + 1 < starts.size) starts[line + 1] - 1 else text.length
        return (starts[line] + character.coerceAtLeast(0)).coerceAtMost(end)
    }

    /** The offset of [position], clamped as [offsetOf]. */
    fun offsetOf(position: Position): Int = offsetOf(position.line, position.character)

    /** The line (0-based) of [offset], clamped to the text. */
    fun lineOf(offset: Int): Int {
        val found = starts.binarySearch(offset.coerceIn(0, text.length))
        return if (found >= 0) found else -found - 2
    }
}
