package dev.sluide.ebooks.view

import dev.sluide.ebooks.ChapterSpan

/** A hit of [BookSearch]: whole-book `[start, end)` in chapter [chapterIndex], with the shortened text around it. */
internal data class SearchMatch(
    val chapterIndex: Int,
    val chapterTitle: String,
    val start: Int,
    val end: Int,
    val snippetBefore: String,
    val matchText: String,
    val snippetAfter: String,
)

/** The hits of one search; [capped] when there were more than the limit. */
internal data class SearchResult(
    val matches: List<SearchMatch>,
    val capped: Boolean,
)

/**
 * Searches the flat text of a book (ADR 0165): case-insensitive without changing offsets, and a run of whitespace in
 * the query matches any run of whitespace in the text (a paragraph break included). No regex; one pass over the text.
 */
internal object BookSearch {
    const val MIN_CHARS = 2
    private const val CONTEXT = 40

    /** Whether [query] has enough non-blank characters to be searched. */
    fun searchable(query: String) = query.count { !isSpace(it) } >= MIN_CHARS

    fun find(
        text: String,
        chapters: List<ChapterSpan>,
        query: String,
        limit: Int = 1000,
    ): SearchResult {
        if (!searchable(query)) return SearchResult(emptyList(), false)
        val words =
            String(
                CharArray(query.length) {
                    if (isSpace(query[it])) ' ' else query[it]
                },
            ).split(' ').filter { it.isNotEmpty() }
        val found = ArrayList<SearchMatch>()
        for (chapter in chapters) {
            val to = chapter.end.coerceAtMost(text.length)
            var at = chapter.start.coerceAtLeast(0)
            while (at < to) {
                val end = matchAt(text, at, to, words)
                if (end < 0) {
                    at++
                    continue
                }
                if (found.size == limit) return SearchResult(found, true)
                found += match(text, chapter, at, end)
                at = end
            }
        }
        return SearchResult(found, false)
    }

    /** The end of a match of [words] starting at [from], or -1. */
    private fun matchAt(
        text: String,
        from: Int,
        to: Int,
        words: List<String>,
    ): Int {
        var at = from
        for ((n, word) in words.withIndex()) {
            if (n > 0) {
                val gapStart = at
                while (at < to && isSpace(text[at])) at++
                if (at == gapStart) return -1
            }
            if (at + word.length > to) return -1
            for (i in word.indices) if (!sameChar(text[at + i], word[i])) return -1
            at += word.length
        }
        return at
    }

    private fun sameChar(
        a: Char,
        b: Char,
    ) = a == b || Character.toLowerCase(Character.toUpperCase(a)) == Character.toLowerCase(Character.toUpperCase(b))

    private fun isSpace(c: Char) = Character.isWhitespace(c) || c == ' '

    private fun match(
        text: String,
        chapter: ChapterSpan,
        start: Int,
        end: Int,
    ): SearchMatch {
        val low = chapter.start.coerceAtLeast(0)
        val high = chapter.end.coerceAtMost(text.length)
        var from = (start - CONTEXT).coerceAtLeast(low)
        if (from > low && !isSpace(text[from]) && !isSpace(text[from - 1])) {
            var cut = from
            while (cut < start && !isSpace(text[cut])) cut++
            if (cut < start) from = cut
        }
        var to = (end + CONTEXT).coerceAtMost(high)
        if (to < high && !isSpace(text[to]) && !isSpace(text[to - 1])) {
            var cut = to
            while (cut > end && !isSpace(text[cut - 1])) cut--
            if (cut > end) to = cut
        }
        return SearchMatch(
            chapter.index,
            chapter.title,
            start,
            end,
            (if (from > low) "… " else "") +
                squash(text.substring(from, start)).let { if (from > low) it.trimStart() else it },
            squash(text.substring(start, end)),
            squash(text.substring(end, to)).let { if (to < high) it.trimEnd() + "…" else it },
        )
    }

    private fun squash(s: String): String {
        val out = StringBuilder(s.length)
        var space = false
        for (c in s) {
            if (isSpace(c)) {
                if (!space) out.append(' ')
                space = true
            } else {
                out.append(c)
                space = false
            }
        }
        return out.toString()
    }
}
