package dev.sluide.translation.text

/**
 * A run of text to translate: [range] (inclusive) from the first to the last char of its text. [gaps] are the line
 * breaks inside it of a paragraph that wraps over lines, each with the indentation and quote markers that follow it;
 * they become one space in the sentence text.
 */
internal class Stretch(
    val range: IntRange,
    val gaps: List<IntRange> = emptyList(),
)

/** The paragraph being collected line by line in Markdown mode, until another block or a hard break ends it. */
internal class Paragraph(
    val first: Int,
    var to: Int,
    val depth: Int,
    var lastLine: IntRange,
    var hardBreak: Boolean,
) {
    val gaps = ArrayList<IntRange>()

    fun stretch() = Stretch(first until to, gaps)
}
