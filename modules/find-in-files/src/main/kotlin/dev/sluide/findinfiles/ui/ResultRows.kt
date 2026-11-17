package dev.sluide.findinfiles.ui

import dev.sluide.fileviewers.revealAround
import dev.sluide.findinfiles.search.FileHits
import dev.sluide.findinfiles.search.LineHit
import dev.sluide.textediting.SearchOptions
import dev.sluide.textediting.TextSearch

/** A row of the result list: a header per file, then a row per matching line. */
internal sealed interface ResultRow {
    val hits: FileHits

    data class Header(
        override val hits: FileHits,
    ) : ResultRow

    data class Line(
        override val hits: FileHits,
        val hit: LineHit,
    ) : ResultRow
}

/** The rows of [files]: a [ResultRow.Header] then one [ResultRow.Line] per line of each. */
internal fun rowsOf(files: List<FileHits>): List<ResultRow> =
    files.flatMap { hits -> listOf<ResultRow>(ResultRow.Header(hits)) + hits.lines.map { ResultRow.Line(hits, it) } }

/** The parts of a header row: [folder] is relative to the root (`""` in the root), [count] "(3)" or "(100+)". */
internal data class HeaderParts(
    val name: String,
    val folder: String,
    val count: String,
)

/** The header text of [hits]; with [withRoot] (hits of several roots) the folder starts with the root's name. */
internal fun headerParts(
    hits: FileHits,
    withRoot: Boolean,
): HeaderParts {
    val relative =
        hits.root
            .relativize(hits.file)
            .parent
            ?.joinToString("/") { it.toString() }
            .orEmpty()
    val rootName =
        hits.root.fileName
            ?.toString()
            .orEmpty()
    val folder = if (withRoot) listOf(rootName, relative).filter { it.isNotEmpty() }.joinToString("/") else relative
    return HeaderParts(hits.file.fileName.toString(), folder, "(${hits.matchCount}${if (hits.capped) "+" else ""})")
}

/** The scope chip's folder name: the last segment of the folder's relative path [label] (`/` or `\` separated). */
internal fun scopeName(label: String): String =
    label.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

private fun lineIndices(rows: List<ResultRow>): List<Int> = rows.indices.filter { rows[it] is ResultRow.Line }

/**
 * The index of the line row [delta] line rows (headers skipped) from [current] (`-1` = none), staying at the ends (no
 * wrap). From none, down goes to the first line row and up to the last. `-1` without line rows.
 */
internal fun moveLine(
    rows: List<ResultRow>,
    current: Int,
    delta: Int,
): Int {
    val lines = lineIndices(rows)
    if (lines.isEmpty()) return -1
    val at = lines.indexOf(current)
    if (at < 0) return if (delta > 0) lines.first() else lines.last()
    return lines[(at + delta).coerceIn(0, lines.lastIndex)]
}

/**
 * The line row a page ([rowsPerPage] rows, headers counted) down ([direction] 1) or up (-1) from [current]: the line
 * row at or after the target row going down, at or before it going up; at the ends the last or first line row.
 */
internal fun pageLine(
    rows: List<ResultRow>,
    current: Int,
    direction: Int,
    rowsPerPage: Int,
): Int {
    val lines = lineIndices(rows)
    if (lines.isEmpty()) return -1
    if (current !in lines) return if (direction > 0) lines.first() else lines.last()
    val target = current + direction * rowsPerPage
    return if (direction > 0) {
        lines.firstOrNull { it >= target } ?: lines.last()
    } else {
        lines.lastOrNull { it <= target } ?: lines.first()
    }
}

/** [index] itself for a line row, the first line row of a header at [index], `-1` for neither. */
internal fun lineRowFor(
    rows: List<ResultRow>,
    index: Int,
): Int =
    when (rows.getOrNull(index)) {
        is ResultRow.Line -> index
        is ResultRow.Header -> if (rows.getOrNull(index + 1) is ResultRow.Line) index + 1 else -1
        null -> -1
    }

/** Where the preview shows the selected line: the [matches] to overlay and the range to scroll into view. */
internal data class PreviewTarget(
    val matches: List<IntRange>,
    val revealStart: Int,
    val revealEnd: Int,
)

/**
 * The preview target of [line] (0-based) of [text] (`\n` line breaks): `matches` are `start until end` offsets into
 * [text] of the query's matches on that line alone, so a changed file still highlights. `null` when the file has no
 * such line. The reveal range is the line with the lines around it ([revealAround]), so it lands roughly in the middle.
 */
internal fun previewTarget(
    text: String,
    line: Int,
    query: String,
    options: SearchOptions,
): PreviewTarget? {
    val own = revealAround(text, line, context = 0) ?: return null
    val reveal = checkNotNull(revealAround(text, line)) { "line $line is in the text" }
    val found = TextSearch.find(text.substring(own), query, options).matches
    val matches = found.map { (it.first + own.first)..(it.last + own.first) }
    return PreviewTarget(matches, reveal.first, reveal.last + 1)
}

/** A piece of a snippet, [hit] when it is one of the highlighted ranges. */
internal data class SnippetSegment(
    val text: String,
    val hit: Boolean,
)

/**
 * [snippet] cut at [highlights] (`start until end` ranges, as `LineHit` has them) into plain and hit pieces; ranges are
 * clamped to the snippet and empty pieces left out.
 */
internal fun snippetSegments(
    snippet: String,
    highlights: List<IntRange>,
): List<SnippetSegment> {
    val segments = ArrayList<SnippetSegment>()
    var at = 0
    for (range in highlights.sortedBy { it.first }) {
        val start = range.first.coerceIn(at, snippet.length)
        val end = (range.last + 1).coerceIn(start, snippet.length)
        if (start > at) segments += SnippetSegment(snippet.substring(at, start), false)
        if (end > start) segments += SnippetSegment(snippet.substring(start, end), true)
        at = end
    }
    if (at < snippet.length) segments += SnippetSegment(snippet.substring(at), false)
    return segments
}
