package dev.sluide.httpclient.complete

import dev.sluide.httpclient.parse.HttpFile
import dev.sluide.httpclient.parse.HttpFileParser
import dev.sluide.httpclient.parse.LineKind

/** What the caret is to complete; every anchor is a whole-document offset. */
internal sealed interface Context {
    val anchor: Int

    /** After an unclosed `{{` on [line]; [closed] if `}}` already follows the caret. */
    data class Variable(
        override val anchor: Int,
        val closed: Boolean,
        val line: Int,
    ) : Context

    data class Method(
        override val anchor: Int,
    ) : Context

    data class Version(
        override val anchor: Int,
    ) : Context

    /** [colonFollows]: the line already has a `:` after the caret; [line] is the caret's line (0-based). */
    data class HeaderName(
        override val anchor: Int,
        val colonFollows: Boolean,
        val line: Int,
    ) : Context

    data class HeaderValue(
        override val anchor: Int,
        val header: String,
    ) : Context

    data class Tag(
        override val anchor: Int,
    ) : Context

    /** Nothing applies; the anchor is the caret. */
    data class Nothing(
        override val anchor: Int,
    ) : Context
}

/**
 * Decides what the caret completes from the parser's line kinds, the caret's line and the lines before it in its block
 * (from the last `###`, or the file start). The line being typed is often still `OTHER` to the parser, so position
 * decides, by these rules:
 * - **Variable**: the line is a request line, URL or header continuation, header, inline body, `< path` or `@name =`
 *   line, and the text before the caret has a `{{` with no `}}` after it.
 * - **Block phases**, by the earlier lines of the block: *prelude* without a request line before; *headers* after the
 *   request line and its URL continuations, until the first blank line, a script, or a response reference;
 *   *after* everything later (scripts and response references, which end the headers), where only variables
 *   complete. The first blank line is still `OTHER` to the parser and part of the headers; the lines after it are body.
 * - In the prelude: a comment line with `# @` or `// @` before the caret completes tags; a request line, or a blank
 *   line (what a request line starts as), completes the method (no whitespace before the caret apart from leading),
 *   or after `METHOD URL ` the version.
 * - In the headers: the first blank line and any other line that is not a comment, script, or continuation completes
 *   a header name when the text before the caret is only name characters, or the value when it is `Name:` followed by
 *   the value so far. A line starting with whitespace is a continuation and completes nothing but variables.
 */
internal object ContextDetector {
    private val VARIABLE_KINDS =
        setOf(
            LineKind.REQUEST_LINE,
            LineKind.URL_CONTINUATION,
            LineKind.HEADER,
            LineKind.HEADER_CONTINUATION,
            LineKind.BODY,
            LineKind.BODY_FILE,
            LineKind.VARIABLE,
        )

    private val TAG_PREFIX = Regex("""(?:#|//)[#/]*\s*@([\w-]*)""")
    private val NAME = Regex("""[\w-]*""")
    private val NAME_WORD = Regex("""[\w-]+""")

    private enum class Phase { PRELUDE, HEADERS, AFTER }

    /** [caret] is within [text]; [file] is `HttpFileParser.parse(text)`. */
    fun detect(
        text: String,
        caret: Int,
        file: HttpFile,
    ): Context {
        val line = Lines.locate(text, caret) ?: return Context.Nothing(caret)
        val prefix = text.substring(line.start, caret)
        val kind = file.lineKinds[line.index]
        variable(kind, prefix, text, line)?.let { return it }
        return when (phase(file.lineKinds, line.index)) {
            Phase.PRELUDE -> prelude(kind, prefix, text.substring(line.start, line.end).isBlank(), line)
            Phase.HEADERS -> if (kind == LineKind.HEADER || kind == LineKind.OTHER) header(prefix, text, line) else null
            Phase.AFTER -> null
        } ?: Context.Nothing(caret)
    }

    private fun variable(
        kind: LineKind,
        prefix: String,
        text: String,
        line: Lines.Line,
    ): Context? {
        if (kind !in VARIABLE_KINDS) return null
        val open = unclosedOpening(prefix)
        if (open < 0) return null
        var column = open + 2
        while (column < prefix.length && (prefix[column] == ' ' || prefix[column] == '\t')) column++
        val rest = text.substring(line.start + prefix.length, line.end).trimStart(' ', '\t')
        return Context.Variable(line.start + column, rest.startsWith("}}"), line.index)
    }

    /** Where the `{{` left open at the end of [prefix] is, pairing from the left as `Template.parse` does; -1 if none. */
    private fun unclosedOpening(prefix: String): Int {
        var from = 0
        while (true) {
            val open = prefix.indexOf("{{", from)
            if (open == -1) return -1
            val close = prefix.indexOf("}}", open + 2)
            if (close == -1) return open
            from = close + 2
        }
    }

    /** Walks back from [index] to the request line of its block, noting a script or response reference after it. */
    private fun phase(
        kinds: List<LineKind>,
        index: Int,
    ): Phase {
        var ended = false
        for (line in index - 1 downTo 0) {
            when (kinds[line]) {
                LineKind.SEPARATOR -> {
                    return Phase.PRELUDE
                }

                LineKind.REQUEST_LINE -> {
                    return if (ended) Phase.AFTER else Phase.HEADERS
                }

                LineKind.SCRIPT, LineKind.RESPONSE_REFERENCE -> {
                    ended = true
                }

                else -> {}
            }
        }
        return Phase.PRELUDE
    }

    private fun prelude(
        kind: LineKind,
        prefix: String,
        blank: Boolean,
        line: Lines.Line,
    ): Context? =
        when {
            kind == LineKind.COMMENT || kind == LineKind.TAG -> {
                TAG_PREFIX
                    .matchEntire(
                        prefix,
                    )?.let { Context.Tag(line.start + prefix.length - it.groupValues[1].length) }
            }

            kind == LineKind.REQUEST_LINE || blank -> {
                requestLine(prefix, line.start)
            }

            else -> {
                null
            }
        }

    private fun requestLine(
        prefix: String,
        lineStart: Int,
    ): Context? {
        val rest = prefix.trimStart()
        val method = rest.takeWhile { !it.isWhitespace() }
        if (method.length == rest.length) return Context.Method(lineStart + prefix.length - rest.length)
        if (method !in HttpFileParser.METHODS) return null
        val afterMethod = rest.substring(method.length).trimStart()
        val url = afterMethod.takeWhile { !it.isWhitespace() }
        if (url.length == afterMethod.length) return null
        val version = afterMethod.substring(url.length).trimStart()
        if (version.any(Char::isWhitespace)) return null
        return Context.Version(lineStart + prefix.length - version.length)
    }

    private fun header(
        prefix: String,
        text: String,
        line: Lines.Line,
    ): Context? {
        val colon = prefix.indexOf(':')
        if (':' !in prefix) {
            val colonFollows = text.substring(line.start, line.end).contains(':')
            return if (NAME.matches(prefix)) Context.HeaderName(line.start, colonFollows, line.index) else null
        }
        val name = prefix.substring(0, colon).trim()
        if (!NAME_WORD.matches(name)) return null
        var column = colon + 1
        if (Catalogs.isListHeader(name)) column = maxOf(column, prefix.lastIndexOf(',') + 1)
        while (column < prefix.length && (prefix[column] == ' ' || prefix[column] == '\t')) column++
        return Context.HeaderValue(line.start + column, name)
    }
}
