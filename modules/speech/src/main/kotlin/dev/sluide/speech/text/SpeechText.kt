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
    ): SpokenText = if (markdown) MarkdownSpeech(shown).spoken() else plain(shown)

    private fun plain(shown: String): SpokenText {
        val out = SpokenTextBuilder()
        var index = if (shown.startsWith('﻿')) 1 else 0
        while (index < shown.length) {
            if (!shown[index].isWhitespace()) {
                out.char(shown[index], index)
                index++
                continue
            }
            val start = index
            var lineBreaks = 0
            while (index < shown.length && shown[index].isWhitespace()) {
                if (isLineBreak(shown, index)) lineBreaks++
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
