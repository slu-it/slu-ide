package dev.sluide.jshell.agent

import jdk.jshell.SourceCodeAnalysis
import jdk.jshell.SourceCodeAnalysis.Completeness

/**
 * One snippet of a document. [start] is where its text begins (may be leading blanks or comments), [codeStart] its
 * first code character, [end] just after its last non-blank character; [source] is what JShell evaluates (may end in
 * an added ';'); a trailing text that is not complete is a piece too. Offsets are UTF-16 code units of the document.
 */
internal class Piece(
    val start: Int,
    val codeStart: Int,
    val end: Int,
    val source: String,
)

/** Splits [doc] as the jshell tool reads a script: line by line, a snippet ends where the analysis says complete. */
internal fun split(
    analysis: SourceCodeAnalysis,
    doc: String,
): List<Piece> {
    val pieces = ArrayList<Piece>()
    var start = 0
    var pos = 0
    while (pos < doc.length) {
        val newline = doc.indexOf('\n', pos)
        val lineEnd = if (newline < 0) doc.length else newline + 1
        pos = lineEnd
        while (start < lineEnd) {
            val chunk = doc.substring(start, lineEnd)
            val info = analysis.analyzeCompletion(chunk)
            val completeness = info.completeness()
            if (completeness == Completeness.EMPTY) {
                // blanks and comments only
                start = lineEnd
            } else if (!completeness.isComplete) {
                break
            } else {
                // `remaining` is the input after the complete snippet (CompletionInfo's contract), which is not empty:
                // the rest of the line, maybe a second snippet on it
                val used = chunk.length - info.remaining().length
                pieces += piece(doc, start, start + used, info.source())
                start += used
            }
        }
    }
    // a trailing text that is not complete; never blank, blank lines are EMPTY above
    if (start < doc.length) {
        pieces += piece(doc, start, doc.length, doc.substring(start))
    }
    return pieces
}

private fun piece(
    doc: String,
    start: Int,
    rawEnd: Int,
    source: String,
): Piece {
    var end = rawEnd
    // JShell takes a line of blanks javac does not skip (U+00A0) for a snippet: such a piece ends at its start
    while (end > start && doc[end - 1].isWhitespace()) end--
    return Piece(start, codeStart(doc, start, end), end, source)
}

/** The first character at or after [from] that is neither blank nor inside a comment (or [limit]). */
internal fun codeStart(
    doc: String,
    from: Int,
    limit: Int,
): Int {
    var i = from
    while (i < limit) {
        when {
            doc[i].isWhitespace() -> {
                i++
            }

            doc.startsWith("//", i) -> {
                val newline = doc.indexOf('\n', i)
                i = if (newline < 0) limit else newline + 1
            }

            doc.startsWith("/*", i) -> {
                val close = doc.indexOf("*/", i + 2)
                i = if (close < 0) limit else close + 2
            }

            else -> {
                return i
            }
        }
    }
    return limit
}

/**
 * The snippets to run for [rangeStart] until [rangeEnd]: with a selection those whose code overlaps it; with a caret
 * (an empty range) the one containing it, else the last one ending before it, else the first one.
 */
internal fun select(
    pieces: List<Piece>,
    rangeStart: Int,
    rangeEnd: Int,
): List<Piece> {
    val overlapping = pieces.filter { it.codeStart < rangeEnd && it.end > rangeStart }
    if (overlapping.isNotEmpty()) return overlapping
    // pieces are in order: the last one whose code starts at or before the caret contains it or ends before it
    return listOfNotNull(pieces.lastOrNull { it.codeStart <= rangeStart } ?: pieces.firstOrNull())
}

/** The 0-based line of [offset] in [doc]. */
internal fun lineOf(
    doc: String,
    offset: Int,
): Int {
    var line = 0
    for (i in 0 until minOf(offset, doc.length)) if (doc[i] == '\n') line++
    return line
}
