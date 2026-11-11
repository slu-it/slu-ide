package dev.sluide.speech

/**
 * The text a reading on a [ReadAloudSurface] covers: the plain text [shown] by the surface, read from the sentence that
 * contains [start] (a sentence that ends right at [start] is not read) up to [end] (exclusive). The chars inside the
 * [skip] ranges (footnote markers, page numbers) are not spoken and separate words like a space. All offsets are in
 * [shown]; the text of [start]'s paragraph before it only places the first sentence. A range with nothing to speak from
 * [start] on finishes at once.
 */
class ReadingText(
    val shown: String,
    val start: Int,
    val end: Int,
    val skip: List<IntRange> = emptyList(),
)
