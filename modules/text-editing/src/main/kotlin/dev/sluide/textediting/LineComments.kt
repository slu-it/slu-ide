package dev.sluide.textediting

/** How a language writes comments: a [line] comment prefix and/or a [block] comment as start and end. */
data class CommentSyntax(
    val line: String?,
    val block: Pair<String, String>?,
) {
    companion object {
        /** Python, YAML, shell. */
        val HASH = CommentSyntax("#", null)

        /** Kotlin, Java, JavaScript, TypeScript, SCSS, Sass, LESS. */
        val SLASHES = CommentSyntax("//", "/*" to "*/")

        /** CSS, which has only block comments. */
        val CSS = CommentSyntax(null, "/*" to "*/")

        /** XML, HTML, Markdown. */
        val XML = CommentSyntax(null, "<!--" to "-->")

        /** SQL. */
        val SQL = CommentSyntax("--", "/*" to "*/")

        /** PowerShell. */
        val POWERSHELL = CommentSyntax("#", "<#" to "#>")

        /** INI files, which have only line comments starting with `;`. */
        val SEMICOLON = CommentSyntax(";", null)

        /** Mermaid diagrams, which have only line comments starting with `%%`. */
        val MERMAID = CommentSyntax("%%", null)

        /** Languages without comments, such as JSON and plain text. */
        val NONE = CommentSyntax(null, null)
    }
}

/** Commenting out lines, as with Shortcut+/ in IntelliJ. */
object LineComments {
    /**
     * Uncomments the non-blank lines the selection or caret touches if all of them are commented, else comments all
     * of them. Line comments go after the leading whitespace all these lines share, followed by a space; a following
     * space is removed with the prefix when uncommenting. Without line comments, the content of each line is wrapped
     * in a block comment instead (`/* x */`, after the line's own indentation); a line counts as commented only if it
     * is one block comment. `null` if the language has no comments or all touched lines are blank.
     */
    fun toggle(
        text: String,
        selection: Selection,
        syntax: CommentSyntax,
    ): TextEdit? {
        val lines =
            touchedLines(text, selection)
                .lineStarts(text)
                .map { start -> start until lineEnd(text, start) }
                .filter { text.substring(it).isNotBlank() }
        if (lines.isEmpty()) return null
        val splices =
            when {
                syntax.line != null -> lineSplices(text, lines, syntax.line)
                syntax.block != null -> blockSplices(text, lines, syntax.block)
                else -> return null
            }
        return spliceEdit(text, splices, selection)
    }

    private fun lineSplices(
        text: String,
        lines: List<IntRange>,
        prefix: String,
    ): List<Splice> {
        if (lines.all { text.startsWith(prefix, contentStart(text, it)) }) {
            return lines.map { line ->
                val start = contentStart(text, line)
                Splice(start, prefix.length + spaceAt(text, start + prefix.length, line), "")
            }
        }
        val indent =
            lines
                .map { text.substring(it.first, contentStart(text, it)) }
                .reduce { common, whitespace -> common.commonPrefixWith(whitespace) }
        return lines.map { Splice(it.first + indent.length, 0, "$prefix ") }
    }

    private fun blockSplices(
        text: String,
        lines: List<IntRange>,
        block: Pair<String, String>,
    ): List<Splice> {
        val (open, close) = block
        val commented =
            lines.all { line ->
                val content = text.substring(contentStart(text, line), contentEnd(text, line))
                content.startsWith(open) && content.indexOf(close, open.length) == content.length - close.length
            }
        if (commented) {
            return lines.flatMap { line ->
                val start = contentStart(text, line)
                val end = contentEnd(text, line)
                val opening = open.length + spaceAt(text, start + open.length, line)
                val closeStart = end - close.length
                val spaceBefore = if (closeStart - 1 >= start + opening && text[closeStart - 1] == ' ') 1 else 0
                listOf(Splice(start, opening, ""), Splice(closeStart - spaceBefore, close.length + spaceBefore, ""))
            }
        }
        return lines.flatMap { line ->
            listOf(Splice(contentStart(text, line), 0, "$open "), Splice(contentEnd(text, line), 0, " $close"))
        }
    }

    private fun contentStart(
        text: String,
        line: IntRange,
    ): Int = line.first + Indentation.leadingWhitespace(text.substring(line)).length

    private fun contentEnd(
        text: String,
        line: IntRange,
    ): Int = line.first + text.substring(line).trimEnd().length

    private fun spaceAt(
        text: String,
        offset: Int,
        line: IntRange,
    ): Int = if (offset <= line.last && text[offset] == ' ') 1 else 0
}
