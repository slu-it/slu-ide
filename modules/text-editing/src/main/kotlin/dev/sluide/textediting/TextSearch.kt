package dev.sluide.textediting

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** How a query matches: case-sensitive or not, only as a whole word, as a regular expression. */
data class SearchOptions(
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false,
    val regex: Boolean = false,
)

/**
 * The [matches] of a query in order, at most as many as asked for ([truncated] if there are more), or an [error]
 * message (with no matches) if the query is an invalid regex or the search took too long.
 */
data class SearchResult(
    val matches: List<IntRange>,
    val error: String? = null,
    val truncated: Boolean = false,
)

/** The outcome of replacing: the edits, or why the query or the replacement is invalid or the search took too long. */
sealed interface ReplaceResult {
    /**
     * The [edits] that replace one match each, in order and not overlapping, with offsets in the original text. Their
     * selections, and [selection], which is the last one's, are the caret after the replacement in the text with all
     * [edits] applied.
     */
    data class Replaced(
        val edits: List<TextEdit>,
        val selection: Selection,
    ) : ReplaceResult {
        val count: Int get() = edits.size

        /** [original] with all [edits] applied. */
        fun applyTo(original: String): String = edits.asReversed().fold(original) { text, edit -> edit.applyTo(text) }
    }

    /** The query or the replacement is invalid, or the search took too long, as [message] tells. */
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
 *
 * Every search gives up after a time budget ([DEFAULT_BUDGET]) with [TOO_LONG], and on a regex nested too deeply for
 * the text with [TOO_COMPLEX], so that a pathological regex (`(a+)+\1b`) cannot block the caller.
 */
object TextSearch {
    /** How long a search may take by default: short enough not to freeze a UI noticeably. */
    val DEFAULT_BUDGET: Duration = 200.milliseconds

    /** The error of a search that ran out of time. */
    const val TOO_LONG = "Search took too long"

    /** The error of a regex whose matching overflows the stack. */
    const val TOO_COMPLEX = "Regular expression too complex"

    private const val WORD = "[\\p{L}\\p{N}_]"

    /** At a word character, not preceded by one; or not at a word character. */
    private const val WORD_START = "(?:(?=$WORD)(?<!$WORD)|(?!$WORD))"

    /** After a word character, not followed by one; or not after a word character. */
    private const val WORD_END = "(?:(?<=$WORD)(?!$WORD)|(?<!$WORD))"

    /** The first [limit] matches of [query] in [text]; none for an empty query. Gives up after [budget]. */
    fun find(
        text: String,
        query: String,
        options: SearchOptions,
        limit: Int = Int.MAX_VALUE,
        budget: Duration = DEFAULT_BUDGET,
    ): SearchResult {
        if (query.isEmpty()) return SearchResult(emptyList())
        val pattern =
            try {
                pattern(query, options)
            } catch (e: PatternSyntaxException) {
                return SearchResult(emptyList(), e.description)
            }
        val matcher = pattern.matcher(DeadlineText(text, budget))
        return bounded({ SearchResult(emptyList(), it) }) {
            val matches = ArrayList<IntRange>()
            var truncated = false
            while (matcher.find()) {
                if (matcher.end() == matcher.start()) continue
                if (matches.size == limit) {
                    truncated = true
                    break
                }
                matches += matcher.start() until matcher.end()
            }
            SearchResult(matches, truncated = truncated)
        }
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
     * of [query]. Gives up after [budget].
     */
    fun replace(
        text: String,
        match: IntRange,
        query: String,
        replacement: String,
        options: SearchOptions,
        budget: Duration = DEFAULT_BUDGET,
    ): ReplaceResult? {
        if (query.isEmpty() || match.first < 0 || match.last >= text.length) return null
        val matcher =
            try {
                pattern(query, options).matcher(DeadlineText(text, budget))
            } catch (e: PatternSyntaxException) {
                return ReplaceResult.Invalid(e.description)
            }
        return bounded({ ReplaceResult.Invalid(it) }) {
            matcher.region(match.first, text.length).useTransparentBounds(true).useAnchoringBounds(false)
            if (!matcher.lookingAt() || matcher.end() != match.last + 1) return@bounded null
            val expanded = StringBuilder()
            invalidReplacement { matcher.appendReplacement(expanded, replacementOf(replacement, options)) }
                ?.let { return@bounded it }
            val inserted = expanded.substring(match.first)
            val selection = Selection(match.first + inserted.length)
            ReplaceResult.Replaced(listOf(TextEdit(match.first, match.last + 1, inserted, selection)), selection)
        }
    }

    /**
     * Replaces all matches, one edit per match; the caret goes to the end of the last replacement. `null` for an
     * empty query or without matches. Gives up after [budget].
     */
    fun replaceAll(
        text: String,
        query: String,
        replacement: String,
        options: SearchOptions,
        budget: Duration = DEFAULT_BUDGET,
    ): ReplaceResult? {
        if (query.isEmpty()) return null
        val matcher =
            try {
                pattern(query, options).matcher(DeadlineText(text, budget))
            } catch (e: PatternSyntaxException) {
                return ReplaceResult.Invalid(e.description)
            }
        val replacementText = replacementOf(replacement, options)
        return bounded({ ReplaceResult.Invalid(it) }) {
            val edits = ArrayList<TextEdit>()
            // appendReplacement appends the text since the previous match, then the replacement.
            val expanded = StringBuilder()
            var previousEnd = 0
            // How much the replacements so far changed the length of the text, for the selections after them.
            var shift = 0
            while (matcher.find()) {
                val start = matcher.start()
                val end = matcher.end()
                expanded.setLength(0)
                invalidReplacement { matcher.appendReplacement(expanded, replacementText) }?.let { return@bounded it }
                val inserted = expanded.substring(start - previousEnd)
                edits += TextEdit(start, end, inserted, Selection(start + shift + inserted.length))
                shift += inserted.length - (end - start)
                previousEnd = end
            }
            edits.lastOrNull()?.let { ReplaceResult.Replaced(edits, it.selection) }
        }
    }

    /** Runs [search]; if it runs out of time or stack, gives what [failed] makes of the error message instead. */
    private inline fun <T> bounded(
        failed: (String) -> T,
        search: () -> T,
    ): T =
        try {
            search()
        } catch (_: SearchTimeoutException) {
            failed(TOO_LONG)
        } catch (_: StackOverflowError) {
            failed(TOO_COMPLEX)
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

/**
 * [text] for a regex matcher that gives up once [budget] has passed from now: reading a character then throws a
 * [SearchTimeoutException]. The clock is read every few thousand characters.
 */
internal class DeadlineText(
    private val text: CharSequence,
    budget: Duration,
) : CharSequence {
    private val deadline = System.nanoTime() + budget.inWholeNanoseconds
    private var reads = 0

    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        if (++reads and CLOCK_MASK == 0 && System.nanoTime() - deadline > 0) throw SearchTimeoutException()
        return text[index]
    }

    override fun subSequence(
        startIndex: Int,
        endIndex: Int,
    ): CharSequence = text.subSequence(startIndex, endIndex)

    override fun toString(): String = text.toString()

    private companion object {
        /** The clock is read on every 4096th character. */
        const val CLOCK_MASK = 0xFFF
    }
}

/** A search ran out of time; without a stack trace, as it is only control flow. */
internal class SearchTimeoutException : RuntimeException(null, null, false, false)
