package dev.sluide.textediting

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** How a query matches: case-sensitive or not, only as a whole word, as a regular expression. */
data class SearchOptions(
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
)

/** The [matches] of a query in order, or an [error] message (with no matches) if the query is an invalid regex. */
data class SearchResult(
    val matches: List<IntRange>,
    val error: String? = null,
)

/** The outcome of replacing: an edit, or why the query or the replacement is invalid. */
sealed interface ReplaceResult {
    /** The [edit] that replaces [count] matches. */
    data class Replaced(
        val edit: TextEdit,
        val count: Int,
    ) : ReplaceResult

    /** The query or the replacement is invalid, as [message] tells. */
    data class Invalid(
        val message: String,
    ) : ReplaceResult
}

/**
 * Finding and replacing in a text. Regular expressions are Java's, with `^` and `$` matching at line breaks.
 * Whole words: a match that starts with a letter, digit or `_` must not be preceded by one, and one that ends with
 * such a character must not be followed by one. Finding skips empty matches; replacing all in regex mode does not, so
 * `^` can prefix every line.
 *
 * In regex mode, the replacement refers to groups with `$1` or `${name}`, and `\n`, `\t`, `\\` and `\$` stand for a
 * line break, a tab, a backslash and a dollar sign. Otherwise the replacement is taken literally.
 */
object TextSearch {
    private const val WORD = "[\\p{L}\\p{N}_]"

    /** At a word character, not preceded by one; or not at a word character. */
    private const val WORD_START = "(?:(?=$WORD)(?<!$WORD)|(?!$WORD))"

    /** After a word character, not followed by one; or not after a word character. */
    private const val WORD_END = "(?:(?<=$WORD)(?!$WORD)|(?<!$WORD))"

    /** All matches of [query] in [text]; none for an empty query. */
    fun find(
        text: String,
        query: String,
        options: SearchOptions,
    ): SearchResult {
        if (query.isEmpty()) return SearchResult(emptyList())
        val pattern =
            try {
                pattern(query, options)
            } catch (e: PatternSyntaxException) {
                return SearchResult(emptyList(), e.description)
            }
        val matcher = pattern.matcher(text)
        val matches =
            buildList {
                while (matcher.find()) {
                    if (matcher.end() > matcher.start()) add(matcher.start() until matcher.end())
                }
            }
        return SearchResult(matches)
    }

    /** The index of the first match starting at or after [offset], wrapping to the first; `null` without matches. */
    fun next(
        matches: List<IntRange>,
        offset: Int,
    ): Int? {
        if (matches.isEmpty()) return null
        return matches.indexOfFirst { it.first >= offset }.takeIf { it >= 0 } ?: 0
    }

    /** The index of the last match starting before [offset], wrapping to the last; `null` without matches. */
    fun previous(
        matches: List<IntRange>,
        offset: Int,
    ): Int? {
        if (matches.isEmpty()) return null
        return matches.indexOfLast { it.first < offset }.takeIf { it >= 0 } ?: matches.lastIndex
    }

    /**
     * Replaces [match] by [replacement]; the caret goes after the replacement. `null` if [match] is no longer a match
     * of [query].
     */
    fun replace(
        text: String,
        match: IntRange,
        query: String,
        replacement: String,
        options: SearchOptions,
    ): ReplaceResult? {
        if (query.isEmpty() || match.first < 0 || match.last >= text.length) return null
        val matcher =
            try {
                pattern(query, options).matcher(text)
            } catch (e: PatternSyntaxException) {
                return ReplaceResult.Invalid(e.description)
            }
        matcher.region(match.first, text.length).useTransparentBounds(true).useAnchoringBounds(false)
        if (!matcher.lookingAt() || matcher.end() != match.last + 1) return null
        val expanded = StringBuilder()
        invalidReplacement { matcher.appendReplacement(expanded, replacementOf(replacement, options)) }
            ?.let { return it }
        val inserted = expanded.substring(match.first)
        return ReplaceResult.Replaced(
            TextEdit(match.first, match.last + 1, inserted, Selection(match.first + inserted.length)),
            1,
        )
    }

    /**
     * Replaces all matches as one edit, from the start of the first match to the end of the last; the caret goes to
     * the end of the replaced span. `null` for an empty query or without matches.
     */
    fun replaceAll(
        text: String,
        query: String,
        replacement: String,
        options: SearchOptions,
    ): ReplaceResult? {
        if (query.isEmpty()) return null
        val matcher =
            try {
                pattern(query, options).matcher(text)
            } catch (e: PatternSyntaxException) {
                return ReplaceResult.Invalid(e.description)
            }
        val replacementText = replacementOf(replacement, options)
        val result = StringBuilder()
        var first = -1
        var last = -1
        var count = 0
        while (matcher.find()) {
            if (first < 0) first = matcher.start()
            last = matcher.end()
            count++
            invalidReplacement { matcher.appendReplacement(result, replacementText) }?.let { return it }
        }
        if (count == 0) return null
        val inserted = result.substring(first)
        return ReplaceResult.Replaced(TextEdit(first, last, inserted, Selection(first + inserted.length)), count)
    }

    /** Runs [append] and turns the errors of an invalid replacement into [ReplaceResult.Invalid]. */
    private fun invalidReplacement(append: () -> Unit): ReplaceResult.Invalid? =
        try {
            append()
            null
        } catch (e: IllegalArgumentException) {
            ReplaceResult.Invalid(e.message ?: "Invalid replacement")
        } catch (e: IndexOutOfBoundsException) {
            ReplaceResult.Invalid(e.message ?: "Invalid replacement")
        }

    private fun pattern(
        query: String,
        options: SearchOptions,
    ): Pattern {
        val expression = if (options.regex) query else Pattern.quote(query)
        val bounded =
            if (options.wholeWord) "$WORD_START(?:$expression)$WORD_END" else expression
        val flags =
            (if (options.regex) Pattern.MULTILINE else 0) or
                (if (options.matchCase) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        return Pattern.compile(bounded, flags)
    }

    private fun replacementOf(
        replacement: String,
        options: SearchOptions,
    ): String = if (options.regex) regexReplacement(replacement) else Matcher.quoteReplacement(replacement)

    /** [replacement] for [Matcher.appendReplacement], with `\n` and `\t` turned into a line break and a tab. */
    private fun regexReplacement(replacement: String): String =
        buildString {
            var index = 0
            while (index < replacement.length) {
                val char = replacement[index]
                if (char == '\\' && index + 1 < replacement.length) {
                    when (val escaped = replacement[index + 1]) {
                        'n' -> append('\n')
                        't' -> append('\t')
                        else -> append(char).append(escaped)
                    }
                    index += 2
                } else {
                    append(char)
                    index++
                }
            }
        }
}
