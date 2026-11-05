package dev.sluide.translation.text

import kotlin.test.assertEquals

/** The sentences of [text] as `Sentence.text`, in order. */
internal fun sentences(
    text: String,
    markdown: Boolean,
): List<String> = TranslationText.segments(text, markdown).filterIsInstance<Segment.Sentence>().map { it.text }

/** The text of the kept segments, in order. */
internal fun kept(
    text: String,
    markdown: Boolean,
): List<String> =
    TranslationText.segments(text, markdown).filterIsInstance<Segment.Kept>().map {
        text.substring(it.range.first, it.range.last + 1)
    }

/**
 * The segments cover [text] without gaps or overlaps, and an identity translation (the sentence text as the model
 * gets it) gives every sentence and the whole text back, except that the line breaks inside a sentence (with their
 * indentation and quote markers) became one space.
 */
internal fun assertRoundTrip(
    text: String,
    markdown: Boolean,
    placeholders: Placeholders = Placeholders.DEFAULT,
) {
    val segments = TranslationText.segments(text, markdown, placeholders = placeholders)
    var expected = 0
    for (segment in segments) {
        assertEquals(expected, segment.range.first, "gap or overlap before ${segment.range} in <$text>")
        expected = segment.range.last + 1
    }
    assertEquals(text.length, expected, "segments end early for <$text>")
    val wholeExpected = StringBuilder()
    for (segment in segments) {
        val original = text.substring(segment.range.first, segment.range.last + 1)
        if (segment is Segment.Sentence) {
            val restored = placeholders.restore(segment.text, segment.spans)
            assertEquals(collapsed(original), collapsed(restored), "sentence <$original> in <$text>")
            wholeExpected.append(restored)
        } else {
            wholeExpected.append(original)
        }
    }
    val identity = TranslationText.assemble(text, segments, placeholders) { it.text }
    assertEquals(wholeExpected.toString(), identity, "whole text <$text>")
}

private val LINE_BREAK = Regex("[ \\t]*\\r?\\n(?: {0,3}>[ \\t]?)*[ \\t]*")

/** [text] with every line break, its trailing blanks and the indentation and quote markers after it as one space. */
private fun collapsed(text: String) = LINE_BREAK.replace(text, " ")
