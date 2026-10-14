package dev.sluide.httpclient.parse

import java.time.Duration

/**
 * A parsed `.http` file. Line numbers are 0-based; values keep their `{{…}}` variables unresolved.
 */
internal data class HttpFile(
    val variables: List<FileVariable>,
    val requests: List<RequestBlock>,
    val problems: List<ParseProblem>,
)

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
