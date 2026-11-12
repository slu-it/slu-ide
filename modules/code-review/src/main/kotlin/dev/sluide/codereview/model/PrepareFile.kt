package dev.sluide.codereview.model

import dev.sluide.codereview.ReviewFile
import dev.sluide.codereview.ReviewFileContent
import dev.sluide.fileviewers.CodeEditors
import dev.sluide.fileviewers.HighlightSpan
import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.WordChanges
import dev.sluide.fileviewers.wordChanges
import java.security.MessageDigest
import java.util.HexFormat

/**
 * A file's content made ready for [ReviewDocument.setContent] by [prepareFile]: lines, highlighting, changed words
 * and the counts of [added] and [removed] lines.
 */
internal class PreparedFile(
    val added: Int,
    val removed: Int,
    /** Differs when either text differs; a changed key unchecks Viewed. */
    val contentKey: String,
    internal val body: PreparedBody,
)

/** What a prepared file shows. */
internal sealed interface PreparedBody {
    data object Binary : PreparedBody

    data object TooLarge : PreparedBody

    /** [detail] is "abc1234 → def5678". */
    data class Submodule(
        val detail: String,
    ) : PreparedBody

    /** Both sides by line, with git's [changes] cut to the line counts, line-relative tokens and changed words. */
    class Text(
        val originalLines: List<String>,
        val currentLines: List<String>,
        val changes: List<LineChange>,
        val originalTokens: List<List<HighlightSpan>>,
        val currentTokens: List<List<HighlightSpan>>,
        val words: WordChanges,
        val ignoreWhitespace: Boolean,
    ) : PreparedBody {
        fun lines(side: ReviewSide) = if (side == ReviewSide.ORIGINAL) originalLines else currentLines

        fun tokens(side: ReviewSide) = if (side == ReviewSide.ORIGINAL) originalTokens else currentTokens

        fun words(side: ReviewSide) = if (side == ReviewSide.ORIGINAL) words.original else words.current
    }
}

/**
 * Prepares [content] of [file] for the document; pure and thread-safe, run on the review thread. Each text is
 * highlighted once as a whole with [highlighter] (not when `null` or the text is over [CodeEditors.HIGHLIGHT_LIMIT])
 * and the tokens are cut per line; [ignoreWhitespace] tells that git hid whitespace changes, so the word diff ignores
 * them too. [file] is not read yet; it is part of the API for rules by file (ADR 0168).
 */
@Suppress("UNUSED_PARAMETER")
internal fun prepareFile(
    file: ReviewFile,
    content: ReviewFileContent,
    highlighter: SyntaxHighlighter?,
    ignoreWhitespace: Boolean,
): PreparedFile =
    when (content) {
        ReviewFileContent.Binary -> {
            PreparedFile(0, 0, "binary", PreparedBody.Binary)
        }

        ReviewFileContent.TooLarge -> {
            PreparedFile(0, 0, "too-large", PreparedBody.TooLarge)
        }

        is ReviewFileContent.Submodule -> {
            val detail = "${shortHash(content.old)} → ${shortHash(content.new)}"
            PreparedFile(0, 0, "submodule ${content.old} ${content.new}", PreparedBody.Submodule(detail))
        }

        is ReviewFileContent.Text -> {
            prepareText(content, highlighter, ignoreWhitespace)
        }
    }

private fun shortHash(hash: String?) = hash?.take(7) ?: "none"

private fun prepareText(
    content: ReviewFileContent.Text,
    highlighter: SyntaxHighlighter?,
    ignoreWhitespace: Boolean,
): PreparedFile {
    val originalLines = textLines(content.original)
    val currentLines = textLines(content.current)
    val changes =
        content.changes
            .map { LineChange(it.original.clampTo(originalLines.size), it.current.clampTo(currentLines.size)) }
            .filterNot { it.original.isEmpty() && it.current.isEmpty() }
    val body =
        PreparedBody.Text(
            originalLines,
            currentLines,
            changes,
            lineTokens(content.original, originalLines, highlighter),
            lineTokens(content.current, currentLines, highlighter),
            wordChanges(originalLines, currentLines, changes, ignoreWhitespace),
            ignoreWhitespace,
        )
    return PreparedFile(
        added = changes.sumOf { it.current.count() },
        removed = changes.sumOf { it.original.count() },
        contentKey = contentKey(content.original, content.current),
        body = body,
    )
}

/** The lines of [text] without line breaks (`\n` or `\r\n`); a final line break does not start another line. */
internal fun textLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = text.split('\n').map { it.removeSuffix("\r") }
    return if (text.endsWith('\n')) lines.dropLast(1) else lines
}

/** This range cut off to the indices of [lines] lines. */
internal fun IntRange.clampTo(lines: Int): IntRange {
    val start = first.coerceIn(0, lines)
    return start until (last + 1).coerceIn(start, lines)
}

/**
 * The tokens of each of [lines] (the lines of [text]), relative to the line: [text] is highlighted as a whole, and a
 * token crossing lines (a multi-line comment) is cut per line. No tokens without [highlighter] or above the limit.
 */
internal fun lineTokens(
    text: String,
    lines: List<String>,
    highlighter: SyntaxHighlighter?,
): List<List<HighlightSpan>> {
    if (highlighter == null || text.isEmpty() || text.length > CodeEditors.HIGHLIGHT_LIMIT) {
        return List(lines.size) { emptyList() }
    }
    val spans = highlighter.highlight(text)
    var lineStart = 0
    var first = 0
    return lines.map { line ->
        val start = lineStart
        val end = start + line.length
        // The line break is `\n`, perhaps after a `\r` that is not part of the line.
        lineStart = text.indexOf('\n', end).let { if (it < 0) text.length else it + 1 }
        while (first < spans.size && spans[first].end <= start) first++
        var index = first
        val tokens = ArrayList<HighlightSpan>()
        while (index < spans.size && spans[index].start < end) {
            val span = spans[index++]
            val from = maxOf(span.start, start)
            val to = minOf(span.end, end)
            if (from < to) tokens += HighlightSpan(from - start, to - start, span.style)
        }
        tokens.ifEmpty { emptyList() }
    }
}

/** A hash of both texts. */
private fun contentKey(
    original: String,
    current: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(original.toByteArray())
    digest.update(0)
    digest.update(current.toByteArray())
    return HexFormat.of().formatHex(digest.digest())
}
