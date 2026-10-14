package dev.sluide.httpclient.parse

import java.time.Duration

/**
 * Parses the IntelliJ HTTP client format (`.http`/`.rest`): requests separated by `###` lines, each made of
 * comments and `# @tag`s, a request line with optional indented URL continuation lines, headers, a blank line and
 * the body, followed by optional response handler scripts. `@name = value` lines outside requests define variables.
 *
 * Comments are recognised before the request line and among the headers; inside the body they are body text.
 */
internal object HttpFileParser {
    val METHODS = setOf("GET", "HEAD", "POST", "PUT", "DELETE", "CONNECT", "PATCH", "OPTIONS", "TRACE")
    private val UNSUPPORTED_METHODS = setOf("WEBSOCKET", "GRAPHQL", "GRPC")

    fun parse(text: String): HttpFile {
        val lines = text.lines()
        val variables = mutableListOf<FileVariable>()
        val requests = mutableListOf<RequestBlock>()
        val problems = mutableListOf<ParseProblem>()
        var start = 0
        var inScript = false
        for ((index, line) in lines.withIndex()) {
            when {
                // A `###` inside a multi-line `{% … %}` script is script text.
                inScript -> {
                    inScript = "%}" !in line
                }

                index > 0 && isSeparator(line) -> {
                    Segment(lines, start, index - 1, variables, problems).parse()?.let(requests::add)
                    start = index
                }

                else -> {
                    inScript = SCRIPT_START.find(line)?.let { "%}" !in line.substring(it.range.last + 1) } ?: false
                }
            }
        }
        Segment(lines, start, lines.lastIndex, variables, problems).parse()?.let(requests::add)
        return HttpFile(variables, requests, problems.sortedBy { it.line })
    }

    internal fun isSeparator(line: String) = line.startsWith("###")

    internal fun isComment(line: String) = line.startsWith("#") || line.startsWith("//")

    private class Segment(
        private val lines: List<String>,
        private val first: Int,
        private val last: Int,
        private val variables: MutableList<FileVariable>,
        private val problems: MutableList<ParseProblem>,
    ) {
        private var separatorName: String? = null
        private var nameTag: String? = null
        private var tags = RequestTags()
        private val scripts = mutableListOf<ScriptBlock>()
        private var requestLine = -1
        private var method = "GET"
        private val urlParts = mutableListOf<String>()
        private var version: HttpVersion? = null
        private val headers = mutableListOf<Header>()
        private val bodyLines = mutableListOf<Int>()
        private var state = State.PRELUDE

        private enum class State { PRELUDE, URL, HEADERS, BODY, AFTER_BODY }

        fun parse(): RequestBlock? {
            var index = first
            if (isSeparator(lines[first])) {
                separatorName = lines[first].trimStart('#').trim().ifEmpty { null }
                index++
            }
            while (index <= last) {
                index = parseLine(index)
            }
            if (requestLine < 0) return null
            val end = (last downTo requestLine).first { lines[it].isNotBlank() }
            val url = urlParts.joinToString("")
            if (url.isEmpty()) problems += ParseProblem(requestLine, "The request has no URL")
            return RequestBlock(
                name = nameTag ?: separatorName,
                lines = first..end,
                requestLine = requestLine,
                method = method,
                url = url,
                version = version,
                headers = headers.toList(),
                body = bodyParts(),
                tags = tags,
                scripts = scripts.toList(),
            )
        }

        /** Handles the line at [index] and returns the index of the next unhandled line. */
        private fun parseLine(index: Int): Int {
            val line = lines[index]
            return when (state) {
                State.PRELUDE -> prelude(index, line)
                State.URL -> url(index, line)
                State.HEADERS -> header(index, line)
                State.BODY -> body(index, line)
                State.AFTER_BODY -> afterBody(index, line)
            }
        }

        private fun prelude(
            index: Int,
            line: String,
        ): Int {
            when {
                line.isBlank() -> {}

                isComment(line) -> {
                    tag(index, line)
                }

                line.startsWith("@") -> {
                    variable(index, line)
                }

                line.startsWith("<") && line.length > 1 && line[1] != '>' -> {
                    return script(index, ScriptBlock.Kind.PRE)
                }

                else -> {
                    requestLine(index, line)
                }
            }
            return index + 1
        }

        private fun url(
            index: Int,
            line: String,
        ): Int {
            if (line.isNotBlank() && line.first().isWhitespace()) {
                urlParts += versionStripped(index, line.trim())
                return index + 1
            }
            state = State.HEADERS
            return index
        }

        private fun header(
            index: Int,
            line: String,
        ): Int {
            when {
                line.isBlank() -> {
                    state = State.BODY
                }

                isComment(line) -> {}

                line.first().isWhitespace() && headers.isNotEmpty() -> {
                    val previous = headers.last()
                    headers[headers.lastIndex] = previous.copy(value = "${previous.value} ${line.trim()}".trim())
                }

                isResponseMarker(line) -> {
                    return responseMarker(index, line)
                }

                else -> {
                    val colon = line.indexOf(':')
                    val name = if (colon > 0) line.substring(0, colon).trim() else ""
                    if (name.isEmpty() || name.any(Char::isWhitespace)) {
                        problems +=
                            ParseProblem(index, "Expected a header (Name: value) or a blank line before the body")
                    } else {
                        headers += Header(name, line.substring(colon + 1).trim(), index)
                    }
                }
            }
            return index + 1
        }

        private fun body(
            index: Int,
            line: String,
        ): Int {
            if (isResponseMarker(line)) return responseMarker(index, line)
            bodyLines += index
            return index + 1
        }

        private fun afterBody(
            index: Int,
            line: String,
        ): Int {
            when {
                isResponseMarker(line) -> {
                    return responseMarker(index, line)
                }

                line.isNotBlank() && !isComment(line) -> {
                    problems +=
                        ParseProblem(index, "Ignored text after the response handler")
                }
            }
            return index + 1
        }

        private fun tag(
            index: Int,
            line: String,
        ) {
            val text = line.trimStart('#', '/').trim()
            val match = TAG.matchEntire(text) ?: return
            val (tag, value) = match.destructured
            when (tag) {
                "name" -> {
                    if (value.isBlank()) {
                        problems += ParseProblem(index, "@name needs a name")
                    } else {
                        nameTag = value.trim()
                    }
                }

                "no-redirect" -> {
                    tags = tags.copy(noRedirect = true)
                }

                "no-cookie-jar" -> {
                    tags = tags.copy(noCookieJar = true)
                }

                "timeout" -> {
                    duration(index, tag, value)?.let { tags = tags.copy(timeout = it) }
                }

                "connection-timeout" -> {
                    duration(index, tag, value)?.let { tags = tags.copy(connectionTimeout = it) }
                }
                // @no-log and unknown tags are plain comments.
            }
        }

        private fun duration(
            index: Int,
            tag: String,
            value: String,
        ): Duration? {
            val match = DURATION.matchEntire(value.trim())
            val amount = match?.groupValues?.get(1)?.toLongOrNull()
            if (match == null || amount == null || amount <= 0) {
                problems += ParseProblem(index, "@$tag needs a positive duration such as 30, 500 ms, 30 s or 2 m")
                return null
            }
            return when (match.groupValues[2]) {
                "ms" -> Duration.ofMillis(amount)
                "m" -> Duration.ofMinutes(amount)
                else -> Duration.ofSeconds(amount)
            }
        }

        private fun variable(
            index: Int,
            line: String,
        ) {
            val match = VARIABLE.matchEntire(line.trimEnd())
            if (match == null) {
                problems += ParseProblem(index, "Expected a variable definition: @name = value")
            } else {
                variables += FileVariable(match.groupValues[1], match.groupValues[2].trim(), index)
            }
        }

        private fun requestLine(
            index: Int,
            line: String,
        ) {
            requestLine = index
            state = State.URL
            val trimmed = line.trim()
            val firstToken = trimmed.substringBefore(' ').substringBefore('\t')
            val rest =
                when (firstToken) {
                    in METHODS -> {
                        method = firstToken
                        trimmed.substring(firstToken.length).trim()
                    }

                    in UNSUPPORTED_METHODS -> {
                        method = firstToken
                        problems += ParseProblem(index, "$firstToken requests are not supported")
                        trimmed.substring(firstToken.length).trim()
                    }

                    else -> {
                        trimmed
                    }
                }
            if (rest.isNotEmpty()) urlParts += versionStripped(index, rest)
        }

        /** [part] without a trailing `HTTP/…` version, which is recorded. */
        private fun versionStripped(
            index: Int,
            part: String,
        ): String {
            val match = VERSION.find(part) ?: return part
            val (number, priorKnowledge) = match.destructured
            when (number) {
                "1.1" -> {
                    version = HttpVersion.HTTP_1_1
                }

                "2", "2.0" -> {
                    version = HttpVersion.HTTP_2
                    if (priorKnowledge.isNotEmpty()) {
                        problems += ParseProblem(index, "HTTP/2 prior knowledge is not supported; HTTP/2 is negotiated")
                    }
                }

                else -> {
                    problems += ParseProblem(index, "HTTP/$number is not supported; HTTP/1.1 is used")
                }
            }
            return part.substring(0, match.range.first).trim()
        }

        /** Reads a `{% … %}` script or a script file reference starting at [index]; returns the line after it. */
        private fun script(
            index: Int,
            kind: ScriptBlock.Kind,
        ): Int {
            val opening = lines[index].substring(1).trim()
            if (!opening.startsWith("{%")) {
                scripts += ScriptBlock(kind, index..index)
                return index + 1
            }
            if (opening.indexOf("%}", 2) >= 0) {
                scripts += ScriptBlock(kind, index..index)
                return index + 1
            }
            val end = (index + 1..last).firstOrNull { "%}" in lines[it] }
            if (end == null) {
                problems += ParseProblem(index, "The script is not closed with %}")
                scripts += ScriptBlock(kind, index..last)
                return last + 1
            }
            scripts += ScriptBlock(kind, index..end)
            return end + 1
        }

        private fun responseMarker(
            index: Int,
            line: String,
        ): Int {
            state = State.AFTER_BODY
            return when {
                line.startsWith("<>") -> {
                    index + 1
                }

                line.startsWith(">>") -> {
                    problems += ParseProblem(index, "Saving the response to a file (>>) is not supported")
                    index + 1
                }

                else -> {
                    script(index, ScriptBlock.Kind.RESPONSE)
                }
            }
        }

        private fun bodyParts(): List<BodyPart> {
            val firstText = bodyLines.indexOfFirst { lines[it].isNotBlank() }
            if (firstText < 0) return emptyList()
            val lastText = bodyLines.indexOfLast { lines[it].isNotBlank() }
            val parts = mutableListOf<BodyPart>()
            val text = mutableListOf<String>()
            for (index in bodyLines.subList(firstText, lastText + 1)) {
                val file = FILE_REF.matchEntire(lines[index])
                if (file == null) {
                    text += lines[index]
                } else {
                    if (text.isNotEmpty()) parts += BodyPart.Text(text.joinToString("\n"))
                    text.clear()
                    parts += BodyPart.FileRef(file.groupValues[1].trim(), index)
                }
            }
            if (text.isNotEmpty()) parts += BodyPart.Text(text.joinToString("\n"))
            return parts
        }

        private fun isResponseMarker(line: String) =
            line.startsWith("<>") || line.startsWith(">>") || RESPONSE_SCRIPT.containsMatchIn(line)
    }

    private val TAG = Regex("""@([\w-]+)\s*(?:=\s*)?(.*)""")
    private val DURATION = Regex("""(\d+)\s*(ms|s|m)?""")
    private val VARIABLE = Regex("""@([A-Za-z_][\w.-]*)\s*=(.*)""")
    private val VERSION = Regex("""\s+HTTP/(\S+)(\s+\(Prior Knowledge\))?\s*$""", RegexOption.IGNORE_CASE)
    private val RESPONSE_SCRIPT = Regex("""^>(\s*\{%|\s+\S)""")

    /** `< path`, also VS Code's `<@ path` and `<@encoding path` (the encoding is ignored). */
    private val FILE_REF = Regex("""<(?:@\S*)?\s+(?!\{%)(\S.*)""")
    private val SCRIPT_START = Regex("""^[<>]\s*\{%""")
}
