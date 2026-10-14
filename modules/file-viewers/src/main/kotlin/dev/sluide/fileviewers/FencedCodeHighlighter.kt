package dev.sluide.fileviewers

/**
 * Highlights Markdown: the text between fenced code blocks with [markdown], the blocks themselves here. The content of
 * a block whose language [languages] knows (` ```ts `, `~~~python`) is highlighted as that language; fence lines and
 * blocks without a language, or with an unknown one, are strings. The Markdown lexer is never asked about fences,
 * since it gets nested ones (```` ````md ```` containing ` ``` `) wrong.
 *
 * Fences follow CommonMark: up to 3 spaces of indent, then at least 3 backticks or tildes, then an info string whose
 * first word is the language. A fence ends at a line with at least as many of the same character and nothing else;
 * an unclosed fence runs to the end of the text. Every block and every stretch of Markdown is highlighted on its own.
 */
internal class FencedCodeHighlighter(
    private val markdown: SyntaxHighlighter,
    private val languages: (String) -> SyntaxHighlighter?,
) : SyntaxHighlighter {
    override fun highlight(text: String): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        var prose = 0
        for (block in codeBlocks(text)) {
            spans += markdown.highlight(text.substring(prose, block.start)).shiftedBy(prose)
            spans += block.spans(text)
            prose = block.end
        }
        spans += markdown.highlight(text.substring(prose)).shiftedBy(prose)
        return spans
    }

    private fun List<HighlightSpan>.shiftedBy(offset: Int) =
        map { HighlightSpan(offset + it.start, offset + it.end, it.style) }

    /**
     * A fenced block from [start] (its opening line) to [end] (after its closing line), with its content from
     * [contentStart] to [contentEnd].
     */
    private class CodeBlock(
        val start: Int,
        val contentStart: Int,
        val contentEnd: Int,
        val end: Int,
        val highlighter: SyntaxHighlighter?,
    ) {
        fun spans(text: String): List<HighlightSpan> {
            val content =
                when {
                    contentStart >= contentEnd -> {
                        emptyList()
                    }

                    highlighter == null -> {
                        listOf(HighlightSpan(contentStart, contentEnd, TokenStyle.STRING))
                    }

                    else -> {
                        highlighter.highlight(text.substring(contentStart, contentEnd)).map {
                            HighlightSpan(contentStart + it.start, contentStart + it.end, it.style)
                        }
                    }
                }
            return listOfNotNull(fenceLine(text, start)) + content + listOfNotNull(fenceLine(text, contentEnd))
        }

        /** The fence line starting at [lineStart] as a string, without its line break; `null` if there is none. */
        private fun fenceLine(
            text: String,
            lineStart: Int,
        ): HighlightSpan? {
            if (lineStart >= end) return null
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0 || it > end) end else it }
            val trimmedEnd = if (lineEnd > lineStart && text[lineEnd - 1] == '\r') lineEnd - 1 else lineEnd
            return if (trimmedEnd > lineStart) HighlightSpan(lineStart, trimmedEnd, TokenStyle.STRING) else null
        }
    }

    private class Fence(
        val start: Int,
        val char: Char,
        val length: Int,
        val contentStart: Int,
        val highlighter: SyntaxHighlighter?,
    ) {
        fun isClosedBy(line: String): Boolean {
            val trimmed = line.trimEnd()
            val marker = trimmed.trimStart(' ')
            return trimmed.length - marker.length <= 3 && marker.length >= length && marker.all { it == char }
        }
    }

    private fun codeBlocks(text: String): List<CodeBlock> {
        val blocks = mutableListOf<CodeBlock>()
        var open: Fence? = null
        var lineStart = 0
        while (lineStart < text.length) {
            val newline = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val lineEnd = minOf(newline + 1, text.length)
            val line = text.substring(lineStart, newline).removeSuffix("\r")
            val fence = open
            if (fence == null) {
                open = openingFence(line, lineStart, contentStart = lineEnd)
            } else if (fence.isClosedBy(line)) {
                blocks += CodeBlock(fence.start, fence.contentStart, lineStart, lineEnd, fence.highlighter)
                open = null
            }
            lineStart = lineEnd
        }
        open?.let { blocks += CodeBlock(it.start, it.contentStart, text.length, text.length, it.highlighter) }
        return blocks
    }

    private fun openingFence(
        line: String,
        lineStart: Int,
        contentStart: Int,
    ): Fence? {
        val match = OPENING.matchEntire(line) ?: return null
        val marker = match.groupValues[1]
        val info = match.groupValues[2].trim()
        if (marker[0] == '`' && '`' in info) return null
        val language = info.split(' ', '\t').first()
        val highlighter = if (language.isEmpty()) null else languages(language.lowercase())
        return Fence(lineStart, marker[0], marker.length, contentStart, highlighter)
    }

    private companion object {
        val OPENING = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")
    }
}
