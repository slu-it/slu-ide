package dev.sluide.codeintelligence.navigation

import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.textediting.TextPosition

/** The characters of an identifier for navigation: letters, numbers and `_` (by code point, so `𝑥` counts). */
private val WORD = Regex("[\\p{L}\\p{N}_]+")

/**
 * The columns `start until end` of the word of [line] at [column]: the word the character at [column] belongs to, or,
 * with [orBefore] (for a caret), also the word that ends at [column]; `null` if there is none.
 */
internal fun wordIn(
    line: String,
    column: Int,
    orBefore: Boolean = false,
): IntRange? {
    for (match in WORD.findAll(line)) {
        val range = match.range
        if (column in range || (orBefore && column == range.last + 1)) return range.first until range.last + 1
        if (range.first > column) return null
    }
    return null
}

/**
 * The editor offsets `start until end` of the word at [offset] ([wordIn]), read from its line only; `null` outside a
 * word.
 */
internal fun ExtensibleCodeEditor.wordAt(
    offset: Int,
    orBefore: Boolean = false,
): IntRange? {
    val position = positionOf(offset)
    if (position.line !in 0 until lineCount) return null
    val columns = wordIn(lineText(position.line), position.column, orBefore) ?: return null
    val lineStart = offsetOf(TextPosition(position.line, 0))
    return lineStart + columns.first until lineStart + columns.last + 1
}
