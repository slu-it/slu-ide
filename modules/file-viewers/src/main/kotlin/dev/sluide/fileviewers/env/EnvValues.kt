package dev.sluide.fileviewers.env

/**
 * Where the values of a `.env` file are, which [EnvFileView] masks. The value of an assignment (`KEY=value`,
 * `export KEY=value`) is what follows the first `=`, without the blanks around it and without a trailing comment (a
 * `#` after a blank, as dotenv reads it); quotes belong to the value. A quoted value whose closing quote is not on its
 * line goes on over the next lines up to that quote, as dotenv reads multi-line values (e.g. a private key), where a
 * `\"` does not close a double-quoted value. Commented-out assignments (`# KEY=value`) have a value too, since an old
 * secret is as secret. Other lines (comments, blank lines) have none.
 */
internal object EnvValues {
    /**
     * The ranges of the values in [text] (`\n` line breaks), at most one per line, as each line shows a part of a
     * multi-line value; none on the lines (0-based) that are [revealed].
     */
    fun ranges(
        text: String,
        revealed: (line: Int) -> Boolean = { false },
    ): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var lineStart = 0
        var line = 0
        // The quote a value spanning lines started with, while inside it.
        var openQuote: Char? = null
        while (lineStart <= text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val content = text.substring(lineStart, lineEnd).removeSuffix("\r")
            val value =
                if (openQuote != null) {
                    continuation(content, openQuote)
                } else {
                    assignment(content)
                }
            openQuote = value?.openQuote
            val range = value?.range
            if (range != null && !range.isEmpty() && !revealed(line)) {
                ranges += lineStart + range.first..lineStart + range.last
            }
            lineStart = lineEnd + 1
            line++
        }
        return ranges
    }

    /**
     * Whether values in the file [name] are masked: not in placeholder files such as `.env.example`, `.env.sample` and
     * `.env.template` (also `.env.local.example`), which hold examples for the real ones.
     */
    fun masks(name: String): Boolean = name.lowercase().substringAfterLast('.') !in PLACEHOLDER_SUFFIXES

    /** The value on a line of its own, if [line] is an assignment. */
    private fun assignment(line: String): Value? {
        val start =
            KEY
                .find(line)
                ?.range
                ?.last
                ?.plus(1) ?: return null
        val first = (start until line.length).firstOrNull { !line[it].isWhitespace() } ?: return null
        val quote = line[first]
        if (quote == '"' || quote == '\'') {
            val close = closingQuote(line, first + 1, quote)
            return if (close >= 0) Value(first..close, null) else Value(first..lastNonBlank(line), quote)
        }
        // An unquoted value ends at a comment; `KEY= # comment` has none.
        if (quote == '#' && first > start) return null
        val end = COMMENT.find(line, first)?.range?.first ?: line.length
        return Value(first..lastNonBlank(line.substring(0, end)), null)
    }

    /** The part of a multi-line value on [line], which goes on after it unless [quote] closes it there. */
    private fun continuation(
        line: String,
        quote: Char,
    ): Value {
        val first = line.indexOfFirst { !it.isWhitespace() }
        val close = closingQuote(line, 0, quote)
        return when {
            close >= 0 -> Value(first..close, null)
            first < 0 -> Value(IntRange.EMPTY, quote)
            else -> Value(first..lastNonBlank(line), quote)
        }
    }

    /** Where [quote] closes a value in [line] at or after [from]; `-1` if it does not. `\"` is no closing `"`. */
    private fun closingQuote(
        line: String,
        from: Int,
        quote: Char,
    ): Int {
        var i = from
        while (i < line.length) {
            when {
                quote == '"' && line[i] == '\\' -> i++
                line[i] == quote -> return i
            }
            i++
        }
        return -1
    }

    private fun lastNonBlank(text: String) = text.indexOfLast { !it.isWhitespace() }

    /** A value's [range] on its line, and the quote it is still open with at the end of the line, if any. */
    private class Value(
        val range: IntRange,
        val openQuote: Char?,
    )

    /** Up to the first `=`: a key, maybe exported or commented out; outside comments anything before the `=`. */
    private val KEY = Regex("""^(?:\s*#\s*(?:export\s+)?[A-Za-z_][\w.-]*\s*=|\s*[^\s#=][^=]*=|\s*=)""")

    /** A comment after an unquoted value. */
    private val COMMENT = Regex("""\s#""")

    private val PLACEHOLDER_SUFFIXES = setOf("example", "sample", "template")
}
