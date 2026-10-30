package dev.sluide.httpclient.curl

import dev.sluide.converting.ReadResult
import dev.sluide.httpclient.auth.DigestAuth
import dev.sluide.httpclient.parse.BodyPart
import dev.sluide.httpclient.parse.HttpFileParser
import dev.sluide.httpclient.parse.LineKind
import dev.sluide.httpclient.parse.RequestBlock
import dev.sluide.httpclient.parse.ScriptBlock

/**
 * Reads the requests of a `.http` text into [CurlRequest]s (ADR 0116) with [HttpFileParser]; `{{variables}}` stay as
 * written, what curl has no place for (in-file variables, scripts) is dropped with a note.
 */
internal object HttpRequestReader {
    private val REQUEST_LINE =
        Regex("""^(?:(?:${HttpFileParser.METHODS.joinToString("|")})\s+\S.*|https?://\S.*)$""")
    private val BASIC = Regex("""Basic\s+(\S+)(?:\s+(.*))?""", RegexOption.IGNORE_CASE)

    /** Whether [text] reads as `.http` requests whose first request line is `METHOD url` or an `http(s)://` URL. */
    fun detects(text: String): Boolean {
        val file = HttpFileParser.parse(text)
        val first = file.requests.firstOrNull() ?: return false
        return file.problems.isEmpty() && REQUEST_LINE.matches(text.lines()[first.requestLine].trim())
    }

    fun read(text: String): ReadResult<List<CurlRequest>> {
        val file = HttpFileParser.parse(text)
        file.problems.firstOrNull()?.let { return ReadResult.Failed(it.message, lineOffset(text, it.line)) }
        val notes = mutableListOf<String>()
        if (file.variables.isNotEmpty()) {
            notes += "${count(file.variables.size, "in-file variable")} dropped: " +
                "${file.variables.joinToString(", ") { it.name }}; their {{…}} stay as written"
        }
        val scripts = file.requests.flatMap { it.scripts }
        scripts.count { it.kind == ScriptBlock.Kind.PRE }.takeIf { it > 0 }?.let {
            notes += "${count(it, "pre-request script")} dropped"
        }
        scripts.count { it.kind == ScriptBlock.Kind.RESPONSE }.takeIf { it > 0 }?.let {
            notes += if (it == 1) "Response handler dropped" else "$it response handlers dropped"
        }
        file.lineKinds.count { it == LineKind.RESPONSE_REFERENCE }.takeIf { it > 0 }?.let {
            notes += "${count(it, "response reference")} dropped"
        }
        return ReadResult.Read(file.requests.map { request(it, notes) }, notes)
    }

    /** [block] as a [CurlRequest]; what it cannot hold exactly is added to [notes]. */
    fun request(
        block: RequestBlock,
        notes: MutableList<String>,
    ): CurlRequest {
        var credentials: Credentials? = null
        val headers =
            block.headers.mapNotNull { header ->
                val found =
                    if (header.name.equals(
                            "Authorization",
                            ignoreCase = true,
                        )
                    ) {
                        credentials(header.value)
                    } else {
                        null
                    }
                if (found != null) credentials = found
                if (found == null) header.name to header.value else null
            }
        val type =
            headers
                .valueOf(CONTENT_TYPE)
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
        val pieces =
            block.body.map {
                when (it) {
                    is BodyPart.Text -> DataPiece.Text(it.text)
                    is BodyPart.FileRef -> DataPiece.File(it.path.removePrefix("./"))
                }
            }
        val multipart = if (type == "multipart/form-data") Multiparts.parts(block.body, headers) else null
        val body =
            when {
                pieces.isEmpty() -> {
                    RequestBody.None
                }

                multipart != null -> {
                    RequestBody.Multipart(multipart)
                }

                type == FORM_TYPE -> {
                    RequestBody.Data(formPieces(pieces))
                }

                else -> {
                    if (type == "multipart/form-data") {
                        notes += "The multipart body cannot be written as -F fields; sent as raw data"
                    } else if (pieces.size > 1) {
                        notes += "curl joins its data with &; the .http body joins its parts with line breaks"
                    }
                    RequestBody.Data(pieces)
                }
            }
        return CurlRequest(
            url = block.url,
            method = block.method,
            headers = if (multipart != null) headers.without(CONTENT_TYPE) else headers,
            body = body,
            version = block.version,
            followRedirects = !block.tags.noRedirect,
            timeout = block.tags.timeout,
            connectTimeout = block.tags.connectionTimeout,
            credentials = credentials,
            name = block.name,
        )
    }

    /** `Basic user password`, `Basic user:password` and their `Digest` forms; null for other values. */
    private fun credentials(value: String): Credentials? {
        DigestAuth.credentials(value)?.let { return Credentials(it.username, it.password, digest = true) }
        val match = BASIC.matchEntire(value.trim()) ?: return null
        val (first, password) = match.destructured
        return when {
            password.isNotEmpty() -> Credentials(first, password)
            ':' in first -> Credentials(first.substringBefore(':'), first.substringAfter(':'))
            else -> null
        }
    }

    /**
     * A form body's pieces as curl's `-d`s: the line breaks the client removes next to `&` go, and so does the `&`
     * between two pieces, which curl adds.
     */
    private fun formPieces(pieces: List<DataPiece>): List<DataPiece> =
        pieces
            .mapIndexed { index, piece ->
                if (piece !is DataPiece.Text) return@mapIndexed piece
                var text = formJoined(piece.text)
                if (index < pieces.lastIndex) text = text.removeSuffix("&")
                if (index > 0) text = text.removePrefix("&")
                DataPiece.Text(text)
            }.filterNot { it is DataPiece.Text && it.text.isEmpty() && pieces.size > 1 }

    /** As the client sends a form ([dev.sluide.httpclient.send.RequestBuilder]). */
    private fun formJoined(text: String): String {
        val result = StringBuilder()
        for (line in text.split('\n')) {
            val continued = result.isEmpty() || result.endsWith("&") || line.trimStart().startsWith("&")
            if (result.isNotEmpty() && !continued) result.append('\n')
            result.append(if (continued) line.trim() else line)
        }
        return result.toString()
    }

    /** The char offset at which [line] (0-based) of [text] starts. */
    internal fun lineOffset(
        text: String,
        line: Int,
    ): Int {
        var offset = 0
        repeat(line) {
            val next = text.indexOf('\n', offset)
            if (next < 0) return offset
            offset = next + 1
        }
        return offset
    }

    private fun count(
        n: Int,
        what: String,
    ) = if (n == 1) "1 $what" else "$n ${what}s"
}
