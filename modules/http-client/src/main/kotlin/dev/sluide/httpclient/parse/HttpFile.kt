package dev.sluide.httpclient.parse

import java.time.Duration

/**
 * A parsed `.http` file. Line numbers are 0-based; values keep their `{{…}}` variables unresolved.
 */
internal data class HttpFile(
    val variables: List<FileVariable>,
    val requests: List<RequestBlock>,
    val problems: List<ParseProblem>,
    /** What each line of the text is to the parser, by line number; highlighting follows it. */
    val lineKinds: List<LineKind>,
) {
    /**
     * The request of the part of the file that holds [line] (0-based): from a `###` (or the start of the file) up to
     * the next one; `null` if that part has no request (e.g. only `@variables` or comments).
     */
    fun requestAt(line: Int): RequestBlock? {
        if (lineKinds.isEmpty()) return null
        val start = (line.coerceIn(0, lineKinds.lastIndex) downTo 0).firstOrNull { lineKinds[it] == LineKind.SEPARATOR }
        return requests.firstOrNull { it.lines.first == (start ?: 0) }
    }
}

/** What a line of a `.http` file is to the parser. */
internal enum class LineKind {
    /** Blank lines outside bodies and text the parser ignores or rejects. */
    OTHER,

    /** `###`, optionally followed by the request's name. */
    SEPARATOR,

    /** `#` or `//`, outside bodies. */
    COMMENT,

    /** A comment before the request line with one of the request tags (`# @name`, `# @timeout`, …). */
    TAG,

    /** `@name = value`. */
    VARIABLE,

    /** Method, URL and version. */
    REQUEST_LINE,

    /** An indented line continuing the URL. */
    URL_CONTINUATION,

    /** `Name: value`. */
    HEADER,

    /** An indented line continuing the previous header's value. */
    HEADER_CONTINUATION,

    /** Inline body text, blank lines included. */
    BODY,

    /** `< path` in the body. */
    BODY_FILE,

    /** A line of a `< {% … %}` or `> {% … %}` script, or a `< file.js`/`> file.js` script reference. */
    SCRIPT,

    /** `<> previous-response` or `>> response-file` after the body. */
    RESPONSE_REFERENCE,
}

/** An in-file variable, `@name = value`. */
internal data class FileVariable(
    val name: String,
    val value: String,
    val line: Int,
)

internal data class ParseProblem(
    val line: Int,
    val message: String,
)

/**
 * One request of a `.http` file.
 *
 * [lines] runs from the `###` separator (or the first line of the file) to the last non-blank line of the request,
 * [requestLine] is the line with the method and URL. [url] is the raw URL with indented continuation lines joined.
 */
internal data class RequestBlock(
    val name: String?,
    val lines: IntRange,
    val requestLine: Int,
    val method: String,
    val url: String,
    val version: HttpVersion?,
    val headers: List<Header>,
    val body: List<BodyPart>,
    val tags: RequestTags,
    val scripts: List<ScriptBlock>,
)

internal enum class HttpVersion {
    HTTP_1_1,
    HTTP_2,
}

internal data class Header(
    val name: String,
    val value: String,
    val line: Int,
)

/** The body is a sequence of inline text and `< path` file references, in the order written. */
internal sealed interface BodyPart {
    /** Inline body lines, joined with `\n`. */
    data class Text(
        val text: String,
    ) : BodyPart

    /** A `< path` line; the path is relative to the `.http` file and may contain variables. */
    data class FileRef(
        val path: String,
        val line: Int,
    ) : BodyPart
}

/** The `# @tag` comments before a request line. Timeouts are null when not given. */
internal data class RequestTags(
    val noRedirect: Boolean = false,
    val noCookieJar: Boolean = false,
    val timeout: Duration? = null,
    val connectionTimeout: Duration? = null,
)

/** A pre-request (`< {% … %}`) or response handler (`> {% … %}`, `> file.js`) script; recorded, never run. */
internal data class ScriptBlock(
    val kind: Kind,
    val lines: IntRange,
) {
    enum class Kind { PRE, RESPONSE }
}
