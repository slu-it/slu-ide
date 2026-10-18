package dev.sluide.fileviewers.markdown

import dev.sluide.fileviewers.preview.Heading
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.CustomNode
import org.commonmark.node.HardLineBreak
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text

/**
 * The headings of Markdown text for the table of contents ([dev.sluide.fileviewers.preview.TableOfContents]). They come
 * from the same parser as the preview ([MarkdownRenderer.parse]), so a `#` line in fenced code or in a math block is
 * not a heading.
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
