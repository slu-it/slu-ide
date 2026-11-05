package dev.sluide.translation.text

/** A stretch of the text of the Translate tab; the segments of a text cover it in order, without gaps or overlaps. */
sealed interface Segment {
    /** Inclusive offsets in the source text. */
    val range: IntRange

    /** Kept verbatim: whitespace, line breaks, code, Markdown markers, and stretches with nothing to translate. */
    data class Kept(
        override val range: IntRange,
    ) : Segment

    /**
     * A sentence for the translation model. [text] is the sentence with its protected spans replaced by placeholder
     * tokens; [spans] are the original spans, in token order (the span at index `i` is the one of `token(i)`).
     */
    data class Sentence(
        override val range: IntRange,
        val text: String,
        val spans: List<String>,
    ) : Segment
}
