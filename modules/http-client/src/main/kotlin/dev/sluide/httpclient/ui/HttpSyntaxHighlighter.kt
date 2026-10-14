package dev.sluide.httpclient.ui

import dev.sluide.fileviewers.HighlightSpan
import dev.sluide.fileviewers.Highlighters
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.fileviewers.TokenStyle
import dev.sluide.httpclient.parse.HttpFile
import dev.sluide.httpclient.parse.HttpFileParser
import dev.sluide.httpclient.parse.LineKind
import dev.sluide.httpclient.vars.Template

/**
 * Highlights `.http` files along the lines [HttpFileParser] sees, so the colours match what runs:
 * - `###` separators and comments as comments, request tags (`# @name`, `# @timeout`, …) as annotations;
 * - `@name = value` definitions: the name as annotation;
 * - the request line: method and `HTTP/…` version as keywords, the URL (and its continuation lines) as string;
 * - header names as properties, their values plain;
 * - `< path` bodies and `<>`/`>>` references: the marker as operator, the path as string;
 * - inline bodies with the highlighter of their request's `Content-Type` ([MediaTypes.language]) from [languages];
 * - `{% … %}` scripts with the JavaScript highlighter, script files as strings;
 * - `{{…}}` variables as annotations on top of everything but comments and scripts.
 *
 * Stateless, so thread-safe as [SyntaxHighlighter] requires.
 */
internal class HttpSyntaxHighlighter(
    private val languages: (String) -> SyntaxHighlighter? = Highlighters::forLanguage,
) : SyntaxHighlighter {
    override fun highlight(text: String): List<HighlightSpan> {
        val file = HttpFileParser.parse(text)
        val lines = Lines(text)
        val spans = mutableListOf<HighlightSpan>()
        val variables = mutableListOf<HighlightSpan>()
        for ((index, kind) in file.lineKinds.withIndex()) {
            val start = lines.start(index)
            val line = lines.content(index)
            spans += lineSpans(kind, line).map { it.shiftedBy(start) }
            if (kind in WITH_VARIABLES) variables += variableSpans(line).map { it.shiftedBy(start) }
        }
        spans += bodySpans(file, lines)
        spans += scriptSpans(file.lineKinds, lines)
        return overlay(spans.sortedBy { it.start }, variables)
    }

    /** The spans of a line of [kind], relative to the line; bodies and scripts are highlighted as a whole elsewhere. */
    private fun lineSpans(
        kind: LineKind,
        line: String,
    ): List<HighlightSpan> =
        when (kind) {
            LineKind.SEPARATOR, LineKind.COMMENT -> listOfNotNull(span(0, line.trimEnd().length, TokenStyle.COMMENT))
            LineKind.TAG -> tagSpans(line)
            LineKind.VARIABLE -> variableDefinitionSpans(line)
            LineKind.REQUEST_LINE -> requestLineSpans(line)
            LineKind.URL_CONTINUATION -> urlSpans(line, 0)
            LineKind.HEADER -> listOfNotNull(headerNameSpan(line))
            LineKind.BODY_FILE -> fileReferenceSpans(line)
            LineKind.RESPONSE_REFERENCE -> markerAndPath(line, 2)
            LineKind.HEADER_CONTINUATION, LineKind.BODY, LineKind.SCRIPT, LineKind.OTHER -> emptyList()
        }

    /** `# @name value`: the comment marker as comment, the tag as annotation, its value plain. */
    private fun tagSpans(line: String): List<HighlightSpan> {
        val tag = TAG.find(line) ?: return listOfNotNull(span(0, line.trimEnd().length, TokenStyle.COMMENT))
        return listOfNotNull(
            span(0, tag.range.first, TokenStyle.COMMENT),
            span(tag.range.first, tag.range.last + 1, TokenStyle.ANNOTATION),
        )
    }

    private fun variableDefinitionSpans(line: String): List<HighlightSpan> {
        val name = HttpFileParser.VARIABLE.matchEntire(line.trimEnd())?.groups[1] ?: return emptyList()
        return listOfNotNull(span(0, name.range.last + 1, TokenStyle.ANNOTATION))
    }

    private fun requestLineSpans(line: String): List<HighlightSpan> {
        val start = line.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return emptyList()
        val methodEnd = line.indexOfFirst(start) { it == ' ' || it == '\t' }
        val method = line.substring(start, methodEnd)
        if (method !in HttpFileParser.METHODS && method !in HttpFileParser.UNSUPPORTED_METHODS) {
            return urlSpans(line, start)
        }
        return listOfNotNull(span(start, methodEnd, TokenStyle.KEYWORD)) + urlSpans(line, methodEnd)
    }

    /** The URL from [from] (leading whitespace skipped) as string, followed by an optional version as keyword. */
    private fun urlSpans(
        line: String,
        from: Int,
    ): List<HighlightSpan> {
        val end = line.trimEnd().length
        val start = line.indexOfFirst(from) { !it.isWhitespace() }
        if (start >= end) return emptyList()
        val version = HttpFileParser.VERSION.find(line, start)
        val urlEnd = version?.range?.first ?: end
        val versionStart = version?.let { line.indexOfFirst(it.range.first) { char -> !char.isWhitespace() } }
        return listOfNotNull(
            span(start, urlEnd, TokenStyle.STRING),
            versionStart?.let { span(it, end, TokenStyle.KEYWORD) },
        )
    }

    private fun headerNameSpan(line: String): HighlightSpan? {
        val colon = line.indexOf(':')
        if (colon < 0) return null
        val start = line.indexOfFirst { !it.isWhitespace() }
        return span(start, line.substring(0, colon).trimEnd().length, TokenStyle.PROPERTY)
    }

    /** `< path` or `<@encoding path`: the marker as operator, the path as string. */
    private fun fileReferenceSpans(line: String): List<HighlightSpan> {
        val path = HttpFileParser.FILE_REF.matchEntire(line)?.groups[1] ?: return emptyList()
        val markerEnd = line.substring(0, path.range.first).trimEnd().length
        return markerAndPath(line, markerEnd)
    }

    /** The first [markerEnd] characters as operator, the rest (trimmed) as string. */
    private fun markerAndPath(
        line: String,
        markerEnd: Int,
    ): List<HighlightSpan> =
        listOfNotNull(
            span(0, markerEnd, TokenStyle.OPERATOR),
            span(line.indexOfFirst(markerEnd) { !it.isWhitespace() }, line.trimEnd().length, TokenStyle.STRING),
        )

    /** The `{{…}}` variables and calls of [line] that the request builder resolves. */
    private fun variableSpans(line: String): List<HighlightSpan> {
        if ("{{" !in line) return emptyList()
        val spans = mutableListOf<HighlightSpan>()
        var offset = 0
        for (part in Template.parse(line).parts) {
            if (part !is Template.Part.Literal) spans += HighlightSpan(offset, offset + part.raw.length, VARIABLE)
            offset += part.raw.length
        }
        return spans
    }

    /**
     * Each run of inline body lines of a request, highlighted as a whole by the language of the request's
     * `Content-Type`. Runs are separated by `< path` lines, as the body parts are.
     */
    private fun bodySpans(
        file: HttpFile,
        lines: Lines,
    ): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        for (request in file.requests) {
            val contentType = request.headers.lastOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value
            val highlighter = MediaTypes.language(contentType)?.let(languages) ?: continue
            var index = request.lines.first
            while (index <= request.lines.last) {
                if (file.lineKinds[index] != LineKind.BODY) {
                    index++
                    continue
                }
                var last = index
                while (last < request.lines.last && file.lineKinds[last + 1] == LineKind.BODY) last++
                spans += highlighted(highlighter, lines.text, lines.start(index), lines.end(last))
                index = last + 1
            }
        }
        return spans
    }

    /**
     * Scripts: the `<`/`>` marker and the `{%`, `%}` delimiters as operators, the code between them highlighted as
     * JavaScript; a script file name as string. A script ends at the first `%}` or, unclosed, with its last line.
     */
    private fun scriptSpans(
        kinds: List<LineKind>,
        lines: Lines,
    ): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        var index = 0
        while (index < kinds.size) {
            if (kinds[index] != LineKind.SCRIPT) {
                index++
                continue
            }
            val start = lines.start(index)
            val line = lines.content(index)
            val open = line.indexOfFirst(1) { !it.isWhitespace() }
            if (!line.startsWith("{%", open)) {
                spans += markerAndPath(line, 1).map { it.shiftedBy(start) }
                index++
                continue
            }
            // As the parser: the script ends on its first line with `%}` (after the `{%`), else with its last line.
            var closeInLine = line.indexOf("%}", open + 2)
            var last = index
            while (closeInLine < 0 && last + 1 < kinds.size && kinds[last + 1] == LineKind.SCRIPT) {
                last++
                closeInLine = lines.content(last).indexOf("%}")
            }
            val codeStart = start + open + 2
            val close = if (closeInLine < 0) null else lines.start(last) + closeInLine
            spans += HighlightSpan(start, start + 1, TokenStyle.OPERATOR)
            spans += HighlightSpan(start + open, codeStart, TokenStyle.OPERATOR)
            val javaScript = languages("javascript")
            if (javaScript != null) spans += highlighted(javaScript, lines.text, codeStart, close ?: lines.end(last))
            if (close != null) spans += HighlightSpan(close, close + 2, TokenStyle.OPERATOR)
            index = last + 1
        }
        return spans
    }

    private fun highlighted(
        highlighter: SyntaxHighlighter,
        text: String,
        start: Int,
        end: Int,
    ): List<HighlightSpan> =
        if (start >= end) emptyList() else highlighter.highlight(text.substring(start, end)).map { it.shiftedBy(start) }

    /** [base] with [overlays] on top: base spans are cut where an overlay lies. Both sorted and disjoint. */
    private fun overlay(
        base: List<HighlightSpan>,
        overlays: List<HighlightSpan>,
    ): List<HighlightSpan> {
        if (overlays.isEmpty()) return base
        val result = mutableListOf<HighlightSpan>()
        var first = 0
        for (span in base) {
            while (first < overlays.size && overlays[first].end <= span.start) first++
            var position = span.start
            var index = first
            while (index < overlays.size && overlays[index].start < span.end) {
                span(position, overlays[index].start, span.style)?.let(result::add)
                position = maxOf(position, overlays[index].end)
                index++
            }
            span(position, span.end, span.style)?.let(result::add)
        }
        return (result + overlays).sortedBy { it.start }
    }

    private fun span(
        start: Int,
        end: Int,
        style: TokenStyle,
    ) = if (start in 0..<end) HighlightSpan(start, end, style) else null

    private fun HighlightSpan.shiftedBy(offset: Int) = HighlightSpan(start + offset, end + offset, style)

    /** Index of the first character from [from] matching [predicate], or the length of the string. */
    private inline fun String.indexOfFirst(
        from: Int,
        predicate: (Char) -> Boolean,
    ): Int {
        for (index in from until length) if (predicate(this[index])) return index
        return length
    }

    /** The lines of [text] as [String.lines] splits them, with their offsets. */
    private class Lines(
        val text: String,
    ) {
        private val contents = text.lines()
        private val starts =
            IntArray(contents.size).also { starts ->
                LINE_BREAK.findAll(text).forEachIndexed { index, match -> starts[index + 1] = match.range.last + 1 }
            }

        fun start(line: Int) = starts[line]

        fun content(line: Int) = contents[line]

        /** The end of [line]'s content, before its line break. */
        fun end(line: Int) = starts[line] + contents[line].length
    }

    private companion object {
        /** The style of `{{…}}` variables, the same as of their `@name` definitions. */
        val VARIABLE = TokenStyle.ANNOTATION

        val WITH_VARIABLES =
            setOf(
                LineKind.VARIABLE,
                LineKind.REQUEST_LINE,
                LineKind.URL_CONTINUATION,
                LineKind.HEADER,
                LineKind.HEADER_CONTINUATION,
                LineKind.BODY,
                LineKind.BODY_FILE,
            )

        val TAG = Regex("""@[\w-]+""")
        val LINE_BREAK = Regex("""\r\n|\r|\n""")
    }
}
