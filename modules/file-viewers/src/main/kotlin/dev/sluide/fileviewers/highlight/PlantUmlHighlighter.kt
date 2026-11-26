package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.HighlightSpan
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.TokenStyle

/**
 * Highlights PlantUML with [lines] (line rules, see `MarkupLanguages.plantuml`) and adds the block comments
 * `/' … '/`, which may span lines: they replace whatever the line rules found inside them. A `/'` after a line
 * comment's `'` at the start of its line is no block, and a block that is not closed runs to the end of the text.
 */
internal class PlantUmlHighlighter(
    private val lines: SyntaxHighlighter,
) : SyntaxHighlighter {
    override fun highlight(text: String): List<HighlightSpan> {
        val blocks =
            COMMENTS
                .findAll(text)
                .filter { it.value.startsWith("/'") }
                .map { HighlightSpan(it.range.first, it.range.last + 1, TokenStyle.COMMENT) }
                .toList()
        // Both lists are sorted and disjoint: one pass, the next block that ends after a span starts is the only
        // one that can overlap it.
        var next = 0
        val spans =
            lines.highlight(text).filter { span ->
                while (next < blocks.size && blocks[next].end <= span.start) next++
                next == blocks.size || blocks[next].start >= span.end
            }
        return (spans + blocks).sortedBy { it.start }
    }

    private companion object {
        /** Line comments (to skip a `/'` in them) and blocks, lazily up to the first `'/` or the end of the text. */
        val COMMENTS = Regex("""(?m)^[ \t]*+'.*+|/'(?s:.*?)(?:'/|\z)""")
    }
}
