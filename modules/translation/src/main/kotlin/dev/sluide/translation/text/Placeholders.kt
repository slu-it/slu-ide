package dev.sluide.translation.text

/** How protected spans (inline code, link targets, URLs) travel through the translation model and come back. */
interface Placeholders {
    /** The token for the span at [index] (0-based position in `Segment.Sentence.spans`; the forms count from 1). */
    fun token(index: Int): String

    /**
     * Puts [spans] back into [translated]; a span whose token the model dropped is appended at the end, after a
     * space; tokens the model mangled slightly (spacing inside the token) are still found where possible.
     */
    fun restore(
        translated: String,
        spans: List<String>,
    ): String

    /**
     * The inclusive ranges in [from] until [to] of [text] that look like a token of this form (with the number of any
     * span, spacing inside tolerated, the closing delimiter optional: `[1` of a citation `[1, 2]` matches too). The
     * source text may hold such text itself; it must be protected as a span, or [restore] would take it for a token.
     */
    fun occurrences(
        text: String,
        from: Int,
        to: Int,
    ): List<IntRange>

    companion object {
        /** `{1}`. */
        val CURLY: Placeholders = DelimitedPlaceholders("{", "}")

        /** `__1__`. */
        val UNDERSCORES: Placeholders = DelimitedPlaceholders("__", "__")

        /** `[1]`. */
        val SQUARE: Placeholders = DelimitedPlaceholders("[", "]")

        /**
         * The form in use, chosen by `OpusMtEngineRealTest`: `[1]` came back unchanged from German, English, Swedish
         * and Chinese; `{1}` and `__1__` broke in Chinese, `(1)` became "punkt 1" in Swedish, and `⟦1⟧` was dropped
         * or repeated up to the length limit.
         */
        val DEFAULT: Placeholders = SQUARE
    }
}

/**
 * Tokens of the form [open] number [close], the number counting from 1; matching ignores spacing inside a token and a
 * missing [close] (the Chinese model turned `[1][2]badge` into `[1[2]bag`).
 */
class DelimitedPlaceholders(
    private val open: String,
    private val close: String,
) : Placeholders {
    // Whitespace is tolerated between the delimiter's characters and around the number: "{ 1 }", "_ _1_ _". The
    // closing delimiter is optional, with its spacing, so a space after "[1" stays in the text.
    private val pattern =
        Regex(
            open.map { Regex.escape(it.toString()) }.joinToString("\\s*") +
                "\\s*(\\d+)(?:\\s*" +
                close.map { Regex.escape(it.toString()) }.joinToString("\\s*") + ")?",
        )

    override fun token(index: Int): String = "$open${index + 1}$close"

    override fun occurrences(
        text: String,
        from: Int,
        to: Int,
    ): List<IntRange> {
        // Cheap exit for the common stretch without the opening char, before a matcher is created.
        if ((from until to).none { text[it] == open[0] }) return emptyList()
        val matcher = pattern.toPattern().matcher(text).region(from, to)
        val found = ArrayList<IntRange>()
        while (matcher.find()) found += matcher.start() until matcher.end()
        return found
    }

    override fun restore(
        translated: String,
        spans: List<String>,
    ): String {
        if (spans.isEmpty()) return translated
        val found = BooleanArray(spans.size)
        val out = StringBuilder(translated.length + 16)
        var last = 0
        for (match in pattern.findAll(translated)) {
            val index = (match.groupValues[1].toIntOrNull() ?: continue) - 1
            if (index !in spans.indices) continue
            out.append(translated, last, match.range.first).append(spans[index])
            found[index] = true
            last = match.range.last + 1
        }
        out.append(translated, last, translated.length)
        for (index in spans.indices) {
            if (found[index]) continue
            if (out.isNotEmpty()) out.append(' ')
            out.append(spans[index])
        }
        return out.toString()
    }
}
