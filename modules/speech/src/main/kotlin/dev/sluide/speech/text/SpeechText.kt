package dev.sluide.speech.text

/** Turns the shown text into what is spoken, keeping where each spoken char came from. */
object SpeechText {
    /**
     * The spoken form of [shown]. Whitespace runs collapse to one space, a blank line becomes a hard break (`"\n"`),
     * leading and trailing whitespace and a byte order mark go. With [markdown], the Markdown markup goes too: front
     * matter, code blocks, HTML tags and comments, heading, emphasis, list, quote, footnote and table markers, link
     * targets and URLs; headings, list items and table rows end a sentence.
     */
    fun prepare(
        shown: String,
        markdown: Boolean,
    ): SpokenText = if (markdown) MarkdownSpeech(shown).spoken() else plain(shown, BooleanArray(0))

    /**
     * The spoken form of the plain text [shown], as [prepare] without Markdown, except that no char inside a [skip]
     * range (offsets of [shown]) is spoken: a skipped range separates words like a space (a footnote marker, a page
     * number). The ranges may be unsorted, overlap or reach outside [shown].
     */
    fun preparePlain(
        shown: String,
        skip: List<IntRange>,
    ): SpokenText = plain(shown, skippedMask(shown.length, skip))

    /**
     * Where to start preparing [shown] with [preparePlain] so that the sentences from [before] on come out as when
     * preparing from 0: the start of the last paragraph break that begins at or before [before] (a run of whitespace
     * and [skip]ped chars with two or more line breaks that are not skipped, spoken as a hard break), or 0. Looks back
     * at most [limit] chars; without such a break there it returns `before - limit`, which may cut a sentence.
     */
    internal fun paragraphStart(
        shown: String,
        before: Int,
        skip: List<IntRange>,
        limit: Int = PARAGRAPH_LOOKBACK,
    ): Int {
        val skipped = skippedMask(shown.length, skip)

        fun isGap(index: Int) = shown[index].isWhitespace() || (index < skipped.size && skipped[index])
        val at = before.coerceIn(0, shown.length)
        val floor = (at - limit).coerceAtLeast(0)
        // A break that [before] lies in counts as a whole: walk back from its end.
        var index = at
        while (index < shown.length && isGap(index)) index++
        var lineBreaks = 0
        while (--index >= floor) {
            if (!isGap(index)) {
                if (lineBreaks >= 2) return index + 1
                lineBreaks = 0
            } else if (!(index < skipped.size && skipped[index]) && isLineBreak(shown, index)) {
                lineBreaks++
            }
        }
        return floor
    }

    /** True at every index of [length] inside a [skip] range; empty without ranges. */
    private fun skippedMask(
        length: Int,
        skip: List<IntRange>,
    ): BooleanArray {
        val skipped = BooleanArray(if (skip.isEmpty()) 0 else length)
        for (range in skip) {
            val from = range.first.coerceAtLeast(0)
            val to = range.last.coerceAtMost(length - 1)
            for (index in from..to) skipped[index] = true
        }
        return skipped
    }

    /** Plain preparation; a char marked in [skipped] (shorter arrays mark nothing past their end) counts as a space. */
    private fun plain(
        shown: String,
        skipped: BooleanArray,
    ): SpokenText {
        fun isSkipped(index: Int) = index < skipped.size && skipped[index]

        fun isGap(index: Int) = shown[index].isWhitespace() || isSkipped(index)
        val out = SpokenTextBuilder()
        var index = if (shown.startsWith('﻿')) 1 else 0
        while (index < shown.length) {
            if (!isGap(index)) {
                out.char(shown[index], index)
                index++
                continue
            }
            val start = index
            var lineBreaks = 0
            while (index < shown.length && isGap(index)) {
                if (!isSkipped(index) && isLineBreak(shown, index)) lineBreaks++
                index++
            }
            if (lineBreaks >= 2) out.paragraph(start) else out.space(start)
        }
        return out.build()
    }

    /** A `\n`, or a `\r` that is not the first half of `\r\n`. */
    private fun isLineBreak(
        text: String,
        index: Int,
    ): Boolean = text[index] == '\n' || (text[index] == '\r' && text.getOrNull(index + 1) != '\n')

    /** How far [paragraphStart] looks back by default. */
    private const val PARAGRAPH_LOOKBACK = 64 * 1024
}

/** Chars with their source offsets, gathered from one or more lines before the inline Markdown is resolved. */
internal class InlineText {
    private val chars = StringBuilder()
    private var sources = IntArray(INITIAL_CAPACITY)

    val length: Int get() = chars.length

    operator fun get(index: Int): Char = chars[index]

    fun source(index: Int): Int = sources[index]

    fun add(
        char: Char,
        source: Int,
    ) {
        if (chars.length == sources.size) sources = sources.copyOf(sources.size * 2)
        sources[chars.length] = source
        chars.append(char)
    }

    fun addRange(
        shown: String,
        from: Int,
        to: Int,
    ) {
        for (index in from until to) add(shown[index], index)
    }

    fun startsWith(
        prefix: String,
        index: Int,
    ): Boolean = chars.startsWith(prefix, index)

    fun substring(
        from: Int,
        to: Int,
    ): String = chars.substring(from, to)

    fun isBlank(): Boolean = chars.isBlank()

    fun clear() = chars.setLength(0)

    private companion object {
        const val INITIAL_CAPACITY = 128
    }
}
