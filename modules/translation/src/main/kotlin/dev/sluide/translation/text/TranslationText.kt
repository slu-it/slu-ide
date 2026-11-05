package dev.sluide.translation.text

import dev.sluide.textediting.Abbreviations
import dev.sluide.textediting.SentenceRanges

/**
 * Splits the text of the Translate tab into segments (what stays verbatim and the sentences for the model) and puts
 * the translation back together. Linear in the text, no recursion.
 */
object TranslationText {
    /** Stands for a protected span while the sentences are split, so no sentence ends inside one. */
    private const val MASK = '\u0001'

    /**
     * The segments of [text] in order, covering it without gaps or overlaps. Plain mode ([markdown] false) keeps
     * whitespace and line breaks and makes sentences of everything else; Markdown mode also keeps what is not prose
     * (see `MarkdownBlocks`) and protects inline spans. Bare URLs and e-mail addresses are protected in both modes.
     * A stretch with no letter outside the protected spans is kept. Text that looks like a placeholder token is
     * protected too, so it comes back verbatim. In Markdown mode a paragraph that wraps over lines is one stretch: its
     * line breaks (with the indentation and quote markers after them) become one space in `Sentence.text`.
     */
    fun segments(
        text: String,
        markdown: Boolean,
        abbreviations: Set<String> = Abbreviations.ENGLISH,
        placeholders: Placeholders = Placeholders.DEFAULT,
    ): List<Segment> {
        val stretches = if (markdown) MarkdownBlocks(text).stretches() else lines(text)
        val segments = ArrayList<Segment>()
        var cursor = 0
        for (stretch in stretches) {
            for (sentence in sentences(text, stretch, markdown, abbreviations, placeholders)) {
                if (cursor < sentence.range.first) segments += Segment.Kept(cursor until sentence.range.first)
                segments += sentence
                cursor = sentence.range.last + 1
            }
        }
        if (cursor < text.length) segments += Segment.Kept(cursor until text.length)
        return segments
    }

    /**
     * [text] with every Kept segment verbatim and every sentence replaced by `translated(it)` put back through
     * [placeholders]; a null (not translated yet) contributes nothing.
     */
    fun assemble(
        text: String,
        segments: List<Segment>,
        placeholders: Placeholders = Placeholders.DEFAULT,
        translated: (Segment.Sentence) -> String?,
    ): String {
        val out = StringBuilder(text.length)
        for (segment in segments) {
            when (segment) {
                is Segment.Kept -> {
                    out.append(text, segment.range.first, segment.range.last + 1)
                }

                is Segment.Sentence -> {
                    translated(segment)?.let { out.append(placeholders.restore(it, segment.spans)) }
                }
            }
        }
        return out.toString()
    }

    /** Plain mode: every line is a stretch (without its line break). */
    private fun lines(text: String): List<Stretch> {
        val lines = ArrayList<Stretch>()
        var start = 0
        while (start < text.length) {
            val newline = text.indexOf('\n', start)
            val lineEnd = if (newline < 0) text.length else newline
            if (lineEnd > start) lines += Stretch(start until lineEnd)
            start = lineEnd + 1
        }
        return lines
    }

    /** A piece of a stretch that is not sentence text as it is: a protected span (token) or a line break (space). */
    private class Edit(
        val range: IntRange,
        val span: Boolean,
    )

    private fun sentences(
        text: String,
        stretch: Stretch,
        markdown: Boolean,
        abbreviations: Set<String>,
        placeholders: Placeholders,
    ): List<Segment.Sentence> {
        val start = stretch.range.first
        val end = stretch.range.last + 1
        val edits = edits(text, stretch, markdown, placeholders)
        val ranges =
            if (edits.isEmpty()) {
                SentenceRanges.split(text.substring(start, end), abbreviations)
            } else {
                val masked = text.substring(start, end).toCharArray()
                for (edit in edits) {
                    masked.fill(if (edit.span) MASK else ' ', edit.range.first - start, edit.range.last + 1 - start)
                }
                SentenceRanges.split(String(masked), abbreviations)
            }
        val result = ArrayList<Segment.Sentence>(ranges.size)
        var next = 0
        for (relative in ranges) {
            val range = (start + relative.first)..(start + relative.last)
            while (next < edits.size && edits[next].range.last < range.first) next++
            val spans = ArrayList<String>()
            val builder = StringBuilder(range.last - range.first + 1)
            var hasLetter = false
            var pos = range.first
            while (next < edits.size && edits[next].range.first <= range.last) {
                val edit = edits[next++]
                hasLetter = hasLetter || hasLetter(text, pos, edit.range.first)
                builder.append(text, pos, edit.range.first)
                if (edit.span) {
                    builder.append(placeholders.token(spans.size))
                    spans += text.substring(edit.range.first, edit.range.last + 1)
                } else {
                    builder.append(' ')
                }
                pos = edit.range.last + 1
            }
            hasLetter = hasLetter || hasLetter(text, pos, range.last + 1)
            builder.append(text, pos, range.last + 1)
            if (hasLetter) result += Segment.Sentence(range, builder.toString(), spans)
        }
        return result
    }

    /**
     * The spans (inline spans and text that looks like a token of [placeholders], so it comes back verbatim;
     * overlapping ones merged) and the line breaks of [stretch], sorted and disjoint; a line break that touches a span
     * is part of the span.
     */
    private fun edits(
        text: String,
        stretch: Stretch,
        markdown: Boolean,
        placeholders: Placeholders,
    ): List<Edit> {
        val start = stretch.range.first
        val end = stretch.range.last + 1
        val found = InlineSpans.find(text, start, end, markdown) + placeholders.occurrences(text, start, end)
        // Overlapping spans are merged: a token look-alike can end inside a link's closer, as `\\[0](api.md)` does.
        val spans = ArrayList<IntRange>(found.size)
        for (span in found.sortedBy { it.first }) {
            val previous = spans.lastOrNull()
            when {
                previous == null || span.first > previous.last -> spans += span
                span.last > previous.last -> spans[spans.lastIndex] = previous.first..span.last
            }
        }
        val edits = ArrayList<Edit>(spans.size + stretch.gaps.size)
        var span = 0
        for (gap in stretch.gaps) {
            while (span < spans.size && spans[span].last < gap.first) edits += Edit(spans[span++], true)
            if (span < spans.size && spans[span].first <= gap.last) continue
            edits += Edit(gap, false)
        }
        while (span < spans.size) edits += Edit(spans[span++], true)
        return edits
    }

    private fun hasLetter(
        text: String,
        from: Int,
        to: Int,
    ): Boolean = (from until to).any { text[it].isLetter() }
}
