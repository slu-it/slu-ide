package dev.sluide.fileviewers

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.CustomNode
import org.commonmark.node.HardLineBreak
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text

/** A heading of a Markdown document: its [level] (1 to 6), plain [text] and 0-based source [line]. */
internal data class Heading(
    val level: Int,
    val text: String,
    val line: Int,
)

/**
 * The headings of Markdown text for the table of contents ([TableOfContents]). They come from the same parser as the
 * preview ([MarkdownRenderer.parse]), so a `#` line in fenced code or in a math block is not a heading.
 */
internal object MarkdownOutline {
    /** Every heading of [markdown] in document order, also those inside lists and quotes. */
    fun headings(markdown: String): List<Heading> {
        val found = mutableListOf<Heading>()
        MarkdownRenderer.parse(markdown).accept(
            object : AbstractVisitor() {
                override fun visit(heading: org.commonmark.node.Heading) {
                    val line = heading.sourceSpans.firstOrNull()?.lineIndex
                    if (line != null) found += Heading(heading.level, plainText(heading), line)
                    super.visit(heading)
                }
            },
        )
        return found
    }

    /** The indentation of each of [headings]: the rank of its level among the levels in use, so `##`, `####` is 0, 1. */
    fun depths(headings: List<Heading>): List<Int> {
        val levels = headings.map { it.level }.distinct().sorted()
        return headings.map { levels.indexOf(it.level) }
    }

    /** The index of the heading whose section contains [line]: the last one at or above it, `null` before the first. */
    fun sectionAt(
        headings: List<Heading>,
        line: Int,
    ): Int? = headings.indexOfLast { it.line <= line }.takeIf { it >= 0 }

    /** The text of [heading] without markup (the TeX of inline math stays); line breaks and whitespace runs become one space. */
    private fun plainText(heading: org.commonmark.node.Heading): String {
        val collector = PlainText()
        heading.accept(collector)
        return collector.text
            .toString()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private class PlainText : AbstractVisitor() {
        val text = StringBuilder()

        override fun visit(text: Text) {
            this.text.append(text.literal)
        }

        override fun visit(code: Code) {
            text.append(code.literal)
        }

        override fun visit(softLineBreak: SoftLineBreak) {
            text.append(' ')
        }

        override fun visit(hardLineBreak: HardLineBreak) {
            text.append(' ')
        }

        override fun visit(customNode: CustomNode) {
            if (customNode is MathInline) text.append(customNode.tex) else super.visit(customNode)
        }
    }
}
