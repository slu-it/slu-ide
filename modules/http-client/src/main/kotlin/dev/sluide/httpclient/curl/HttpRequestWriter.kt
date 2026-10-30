package dev.sluide.httpclient.curl

import dev.sluide.converting.WriteResult
import dev.sluide.httpclient.parse.HttpFileParser
import dev.sluide.httpclient.parse.HttpVersion
import java.time.Duration
import java.util.Base64

/**
 * Writes [CurlRequest]s as requests of a `.http` file (ADR 0116), separated by `###` lines: tags, request line,
 * headers, a blank line and the body; files as `< path` lines, multipart bodies laid out with a boundary as the HTTP
 * client sends them.
 */
internal object HttpRequestWriter {
    const val BOUNDARY = "WebAppBoundary"

    /** Fails when a body has a line that a `.http` file would read as syntax, e.g. `< file` or `###`. */
    fun write(requests: List<CurlRequest>): WriteResult {
        val notes = linkedSetOf<String>()
        val text =
            requests
                .mapIndexed { index, request ->
                    val written =
                        try {
                            request(request, notes)
                        } catch (e: BodySyntaxException) {
                            val which = if (requests.size > 1) "Request ${index + 1}: " else ""
                            return WriteResult.Failed(which + e.message)
                        }
                    val separator =
                        when {
                            request.name != null -> "### ${request.name.replace('\n', ' ')}\n"
                            index > 0 -> "###\n"
                            else -> ""
                        }
                    separator + written
                }.joinToString("\n\n", postfix = "\n")
        return WriteResult.Written(if (requests.isEmpty()) "" else text, notes.toList())
    }

    private fun request(
        request: CurlRequest,
        notes: MutableSet<String>,
    ): String {
        val lines = mutableListOf<String>()
        if (!request.followRedirects) lines += "# @no-redirect"
        request.timeout?.let { lines += "# @timeout ${duration(it)}" }
        request.connectTimeout?.let { lines += "# @connection-timeout ${duration(it)}" }
        if (request.insecure) {
            notes += "--insecure has no .http equivalent; turn off verifyHostCertificate in the environment's " +
                "SSLConfiguration instead"
        }
        val version =
            when (request.version) {
                HttpVersion.HTTP_2 -> " HTTP/2"
                HttpVersion.HTTP_1_1 -> " HTTP/1.1"
                null -> ""
            }
        lines += "${request.method} ${request.url}$version"
        var headers = request.headers
        if (request.body is RequestBody.Multipart) {
            val type = headers.valueOf(CONTENT_TYPE)
            headers = headers.without(CONTENT_TYPE) +
                (
                    CONTENT_TYPE to
                        when {
                            type == null -> "multipart/form-data; boundary=$BOUNDARY"
                            "boundary=" in type -> type
                            else -> "$type; boundary=$BOUNDARY"
                        }
                )
        }
        request.credentials?.let { headers = headers + ("Authorization" to authorization(it)) }
        for ((name, value) in headers) lines += "$name: $value"
        val body = body(request, headers, notes)
        if (body.isNotEmpty()) lines += listOf("") + body
        return lines.joinToString("\n")
    }

    /** As the HTTP client reads it ([dev.sluide.httpclient.auth.BasicAuth]): `Basic user password`. */
    private fun authorization(credentials: Credentials): String {
        val scheme = if (credentials.digest) "Digest" else "Basic"
        return when {
            credentials.user.any(Char::isWhitespace) -> {
                val encoded =
                    Base64.getEncoder().encodeToString(
                        "${credentials.user}:${credentials.password}".toByteArray(),
                    )
                "$scheme $encoded"
            }

            credentials.password.isEmpty() -> {
                "$scheme ${credentials.user}:"
            }

            else -> {
                "$scheme ${credentials.user} ${credentials.password}"
            }
        }
    }

    private fun body(
        request: CurlRequest,
        headers: List<Pair<String, String>>,
        notes: MutableSet<String>,
    ): List<String> =
        when (val body = request.body) {
            RequestBody.None -> {
                emptyList()
            }

            is RequestBody.Data -> {
                val form =
                    headers
                        .valueOf(CONTENT_TYPE)
                        ?.substringBefore(';')
                        ?.trim()
                        .equals(FORM_TYPE, true)
                if (!form && body.pieces.size > 1) {
                    notes += "curl joins its data with &; the .http body joins its parts with line breaks"
                }
                val lines = mutableListOf<String>()
                body.pieces.forEachIndexed { index, piece ->
                    when (piece) {
                        is DataPiece.Text -> {
                            // In a form body, the client removes line breaks next to & (RequestBuilder).
                            val joined = if (form && index < body.pieces.lastIndex) piece.text + "&" else piece.text
                            lines += checked(joined.split('\n'), lines.size)
                        }

                        is DataPiece.File -> {
                            if (!piece.binary) {
                                notes +=
                                    "-d @${piece.path} sends the file without line breaks; .http sends it as it is"
                            }
                            if (form && index > 0 && body.pieces[index - 1] is DataPiece.File) lines += "&"
                            lines += fileLine(piece.path)
                        }
                    }
                }
                lines
            }

            is RequestBody.Multipart -> {
                val lines = mutableListOf<String>()
                body.parts.forEach { lines += part(it, lines.size) }
                lines + "--$BOUNDARY--"
            }
        }

    private fun part(
        part: FormPart,
        first: Int,
    ): List<String> {
        val disposition = "Content-Disposition: form-data; name=\"${part.name}\""
        val type = listOfNotNull(part.type?.let { "Content-Type: $it" })
        return when (val content = part.content) {
            is FormContent.Value -> {
                val head = listOf("--$BOUNDARY", disposition) + type + ""
                head + checked(content.text.split('\n'), first + head.size)
            }

            is FormContent.FileText -> {
                listOf("--$BOUNDARY", disposition) + type + "" + fileLine(content.path)
            }

            is FormContent.File -> {
                val filename = content.filename ?: fileName(content.path)
                listOf("--$BOUNDARY", "$disposition; filename=\"$filename\"") + type + "" + fileLine(content.path)
            }
        }
    }

    /** [lines] of body text from body line [first] (0-based); a line that `.http` reads as syntax is an error. */
    private fun checked(
        lines: List<String>,
        first: Int,
    ): List<String> {
        lines.forEachIndexed { index, line ->
            syntaxOf(line)?.let { throw BodySyntaxException("Line ${first + index + 1} of the body $it") }
        }
        return lines
    }

    /** Why a `.http` file would not read [line] of a body as text, `null` if it would. */
    private fun syntaxOf(line: String): String? =
        when {
            HttpFileParser.isSeparator(line) -> {
                "starts with '###', which a .http file reads as the start of another request"
            }

            line.startsWith("<>") -> {
                "starts with '<>', which a .http file reads as a reference to a previous response"
            }

            HttpFileParser.FILE_REF.matches(line) -> {
                "starts with '<', which a .http file reads as a file to include"
            }

            // As HttpFileParser: `< {%` without `%}` on its line opens a script, which hides the `###` lines after it.
            SCRIPT_START.find(line)?.let { "%}" !in line.substring(it.range.last + 1) } == true -> {
                "starts with '< {%' without '%}', which a .http file reads as the start of a script"
            }

            line.startsWith(">>") || RESPONSE_SCRIPT.containsMatchIn(line) -> {
                "starts with '>', which a .http file reads as a response handler"
            }

            else -> {
                null
            }
        }

    private class BodySyntaxException(
        message: String,
    ) : RuntimeException(message)

    private val SCRIPT_START = Regex("""^<\s*\{%""")
    private val RESPONSE_SCRIPT = Regex("""^>(\s*\{%|\s+\S)""")

    /** `< path`, relative paths starting with `./` as in IntelliJ's examples. */
    private fun fileLine(path: String): String {
        val relative =
            !path.startsWith("/") && !path.startsWith("./") && !path.startsWith("../") &&
                !path.startsWith("{{") && !Regex("""^[A-Za-z]:[\\/]""").containsMatchIn(path)
        return "< " + (if (relative) "./$path" else path)
    }

    internal fun fileName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    private fun duration(duration: Duration): String {
        val millis = duration.toMillis()
        return if (millis % 1000 == 0L) "${millis / 1000} s" else "$millis ms"
    }
}
