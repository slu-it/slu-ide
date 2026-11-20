package dev.sluide.jshell.agent

/**
 * The document range of a diagnostic of [piece]: JShell's positions count from the start of [Piece.source], which
 * starts at [Piece.start]; a position past the piece (an added ';') or none at all (-1) is clamped into it.
 */
internal fun diagnosticRange(
    piece: Piece,
    startPosition: Long,
    endPosition: Long,
): Pair<Int, Int> {
    val start = (piece.start + startPosition).coerceIn(piece.start.toLong(), piece.end.toLong()).toInt()
    val end = (piece.start + endPosition).coerceIn(start.toLong(), piece.end.toLong()).toInt()
    return start to end
}

/**
 * The 0-based document line of a stack frame in a snippet of this session: JShell names a snippet's frames by file
 * `#<snippet id>` and counts lines from 1 within the snippet; [snippetLines] holds each snippet's first document line.
 * Null for frames outside the session's snippets.
 */
internal fun frameLine(
    fileName: String?,
    lineNumber: Int,
    snippetLines: Map<String, Int>,
): Int? {
    if (fileName == null || !fileName.startsWith("#")) return null
    val snippetLine = snippetLines[fileName.substring(1)] ?: return null
    return snippetLine + lineNumber - 1
}
