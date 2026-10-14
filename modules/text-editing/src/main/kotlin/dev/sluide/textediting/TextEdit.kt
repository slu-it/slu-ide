package dev.sluide.textediting

/** A selection in a text, or just the caret if [anchor] and [caret] are equal. */
data class Selection(
    val anchor: Int,
    val caret: Int,
) {
    /** Just a caret at [offset]. */
    constructor(offset: Int) : this(offset, offset)

    val start: Int get() = minOf(anchor, caret)
    val end: Int get() = maxOf(anchor, caret)
    val isEmpty: Boolean get() = anchor == caret
}

/**
 * One editing operation: the text from [start] to [end] (exclusive) is replaced by [text], then [selection] is set.
 * Offsets are those of a text with `\n` line breaks; one edit is meant to be one undo step.
 */
data class TextEdit(
    val start: Int,
    val end: Int,
    val text: String,
    val selection: Selection,
) {
    /** [original] with this edit applied. */
    fun applyTo(original: String): String = original.replaceRange(start, end, text)
}
