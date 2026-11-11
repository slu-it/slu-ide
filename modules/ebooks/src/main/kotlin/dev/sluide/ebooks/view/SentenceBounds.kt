package dev.sluide.ebooks.view

/** Finds the sentence around an offset of a book's flat text, for the visual start mark only (ADR 0165). */
internal object SentenceBounds {
    /**
     * The sentence around [offset] as a range of [text]: from after the previous `.`, `!` or `?` that is followed by
     * whitespace or a line break (or after a paragraph break) up to and including the next one; it never crosses a
     * blank line. Leading whitespace is skipped. An empty text gives an empty range.
     */
    fun around(
        text: String,
        offset: Int,
    ): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val at = offset.coerceIn(0, text.length - 1)
        var start = at
        while (start > 0 && !startsAfter(text, start)) start--
        var end = at
        while (end < text.length - 1 && !endsAt(text, end)) end++
        while (start < end && text[start].isWhitespace()) start++
        return start..end
    }

    private fun isTerminator(c: Char) = c == '.' || c == '!' || c == '?'

    /** Whether a sentence starts at [i]: after a terminator and whitespace, or after a paragraph break. */
    private fun startsAfter(
        text: String,
        i: Int,
    ): Boolean = i >= 2 && (isBreakBefore(text, i) || (text[i - 1].isWhitespace() && isTerminator(text[i - 2])))

    private fun isBreakBefore(
        text: String,
        i: Int,
    ) = text[i - 1] == '\n' && text[i - 2] == '\n'

    /** Whether the sentence containing [i] ends with the char at [i]. */
    private fun endsAt(
        text: String,
        i: Int,
    ): Boolean =
        (i + 2 < text.length && text[i + 1] == '\n' && text[i + 2] == '\n') ||
            (isTerminator(text[i]) && text[i + 1].isWhitespace())
}
