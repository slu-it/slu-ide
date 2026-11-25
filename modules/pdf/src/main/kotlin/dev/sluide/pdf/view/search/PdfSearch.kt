package dev.sluide.pdf.view.search

/** A hit of [PdfSearch]: `[start, end)` in the plain text of [page] (0-based), with the shortened text around it. */
internal data class SearchHit(
    val page: Int,
    val start: Int,
    val end: Int,
    val before: String,
    val match: String,
    val after: String,
)

/** The hits of one search, in page order; [capped] when there were more than the cap, [noText] when every page is blank. */
internal data class SearchResult(
    val hits: List<SearchHit>,
    val capped: Boolean,
    val noText: Boolean,
)

/**
 * Searches the plain texts of a PDF's pages (adapted from the ebook reader's `BookSearch`, ADR 0192): case-insensitive
 * without changing offsets, and a run of whitespace in the query matches any run of whitespace in the text, a line end
 * included. A match never crosses pages. No regex; one pass over each text.
 */
internal object PdfSearch {
    const val MIN_CHARS = 2
    const val CAP = 1000
    private const val CONTEXT = 40

    /** Whether [query] has enough non-blank characters to be searched. */
    fun searchable(query: String) = query.count { !isSpace(it) } >= MIN_CHARS

    fun find(
        pageTexts: List<String>,
        query: String,
        cap: Int = CAP,
    ): SearchResult {
        val noText = pageTexts.all { text -> text.all(::isSpace) }
        if (!searchable(query)) return SearchResult(emptyList(), false, noText)
        val words =
            String(
                CharArray(query.length) {
                    if (isSpace(query[it])) ' ' else query[it]
                },
            ).split(' ').filter { it.isNotEmpty() }
        val found = ArrayList<SearchHit>()
        for ((page, text) in pageTexts.withIndex()) {
            var at = 0
            while (at < text.length) {
                val end = matchAt(text, at, words)
                if (end < 0) {
                    at++
                    continue
                }
                if (found.size == cap) return SearchResult(found, true, noText)
                found += hit(text, page, at, end)
                at = end
            }
        }
        return SearchResult(found, false, noText)
    }

    /** The end of a match of [words] starting at [from], or -1. */
    private fun matchAt(
        text: String,
        from: Int,
        words: List<String>,
    ): Int {
        var at = from
        for ((n, word) in words.withIndex()) {
            if (n > 0) {
                val gapStart = at
                while (at < text.length && isSpace(text[at])) at++
                if (at == gapStart) return -1
            }
            if (at + word.length > text.length) return -1
            for (i in word.indices) if (!sameChar(text[at + i], word[i])) return -1
            at += word.length
        }
        return at
    }

    private fun sameChar(
        a: Char,
        b: Char,
    ) = Character.toLowerCase(Character.toUpperCase(a)) == Character.toLowerCase(Character.toUpperCase(b))

    private fun isSpace(c: Char) = Character.isWhitespace(c) || c == '\u00a0'

    /** The hit `[start, end)` of [page], with about [CONTEXT] chars of [text] either side, cut at a word gap. */
    private fun hit(
        text: String,
        page: Int,
        start: Int,
        end: Int,
    ): SearchHit {
        var from = (start - CONTEXT).coerceAtLeast(0)
        // A word cut at the left is left out, unless it runs up to the match.
        if (from > 0 && !isSpace(text[from - 1])) {
            var cut = from
            while (cut < start && !isSpace(text[cut])) cut++
            if (cut < start) from = cut
        }
        var to = (end + CONTEXT).coerceAtMost(text.length)
        // Likewise a word cut at the right, unless it runs on from the match.
        if (to < text.length && !isSpace(text[to])) {
            var cut = to
            while (cut > end && !isSpace(text[cut - 1])) cut--
            if (cut > end) to = cut
        }
        return SearchHit(
            page,
            start,
            end,
            (if (from > 0) "… " else "") + squash(text.substring(from, start)).trimStart(),
            squash(text.substring(start, end)),
            squash(text.substring(end, to)).trimEnd() + (if (to < text.length) "…" else ""),
        )
    }

    private fun squash(s: String): String {
        val out = StringBuilder()
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
