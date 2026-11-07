package dev.sluide.languageservers

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * LSP positions in a text with `\n` line ends (the editor normalizes them): lines from 0, characters in UTF-16 code
 * units, so a character outside the Basic Multilingual Plane counts twice, as an offset into a Kotlin string does.
 */
internal object TextPositions {
    /** The position of [offset] in [text], clamped to the text. */
    fun positionOf(
        text: CharSequence,
        offset: Int,
    ): Position {
        val clamped = offset.coerceIn(0, text.length)
        var line = 0
        var lineStart = 0
        for (i in 0 until clamped) {
            if (text[i] == '\n') {
                line++
                lineStart = i + 1
            }
        }
        return Position(line, clamped - lineStart)
    }

    /** The offset of [position] in [text]: a line past the end is the end, a character past its line the line's end. */
    fun offsetOf(
        text: CharSequence,
        position: Position,
    ): Int {
        if (position.line < 0) return 0
        var lineStart = 0
        repeat(position.line) {
            val newline = text.indexOf('\n', lineStart)
            if (newline < 0) return text.length
            lineStart = newline + 1
        }
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        return (lineStart + position.character.coerceAtLeast(0)).coerceAtMost(lineEnd)
    }

    /** The range of [start, end) in [text]. */
    fun rangeOf(
        text: CharSequence,
        start: Int,
        end: Int,
    ): Range {
        val from = positionOf(text, start)
        if (end == start) return Range(from, Position(from.line, from.character))
        // Continue from the start instead of scanning the text again.
        var line = from.line
        var lineStart = start - from.character
        for (i in start until end.coerceAtMost(text.length)) {
            if (text[i] == '\n') {
                line++
                lineStart = i + 1
            }
        }
        return Range(from, Position(line, end.coerceAtMost(text.length) - lineStart))
    }
}
