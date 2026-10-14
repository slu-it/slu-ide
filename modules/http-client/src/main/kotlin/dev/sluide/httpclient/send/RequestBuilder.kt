package dev.sluide.httpclient.send

import dev.sluide.httpclient.auth.BasicAuth
import dev.sluide.httpclient.auth.DigestAuth
import dev.sluide.httpclient.parse.BodyPart
import dev.sluide.httpclient.parse.HttpFileParser
import dev.sluide.httpclient.parse.HttpVersion
import dev.sluide.httpclient.parse.RequestBlock
import dev.sluide.httpclient.vars.UnresolvedVariablesException
import dev.sluide.httpclient.vars.VariableResolver
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/**
 * Builds the [PreparedRequest] of a [RequestBlock] of [httpFile]: resolves variables everywhere, reads body files
 * (relative to [httpFile]; text files get their variables replaced where they have a value, with a note for the
 * others, other files are sent as they are), lays out multipart and form bodies, encodes Basic credentials, takes
 * Digest credentials out of the headers and adds default headers.
 *
 * Throws [UnresolvedVariablesException] with every missing variable, or [RequestBuildException].
 */
internal class RequestBuilder(
    private val httpFile: Path,
    private val resolver: VariableResolver,
) {
    fun build(block: RequestBlock): PreparedRequest {
        if (block.method !in HttpFileParser.METHODS) {
            throw RequestBuildException("${block.method} requests are not supported")
        }
        val unresolved = linkedSetOf<String>()

        /** [text] resolved, or null (with the names collected) when variables are missing. */
        fun resolve(text: String): String? =
            try {
                resolver.resolve(text)
            } catch (e: UnresolvedVariablesException) {
                unresolved += e.names
                null
            }

        val url = resolve(block.url)
        val headers = block.headers.map { (resolve(it.name) ?: it.name) to (resolve(it.value) ?: it.value) }
        val contentType = headers.lastOrNull { it.first.equals(CONTENT_TYPE, ignoreCase = true) }?.second.orEmpty()
        val notes = mutableListOf<String>()
        val body = Body(block.body, ::resolve, notes).bytes(contentType)
        if (unresolved.isNotEmpty() || url == null) throw UnresolvedVariablesException(unresolved.toList())

        if (block.scripts.isNotEmpty()) notes += "Scripts are not supported; ${block.scripts.size} script(s) not run"
        val uri = RequestUrls.toUri(url, headers.lastOrNull { it.first.equals("Host", ignoreCase = true) }?.second)
        val digest = headers.firstNotNullOfOrNull { (name, value) -> digestCredentials(name, value) }
        return PreparedRequest(
            uri,
            block.method,
            block.version ?: HttpVersion.HTTP_1_1,
            headers(headers, notes),
            body,
            notes,
            digest,
        )
    }

    private fun digestCredentials(
        name: String,
        value: String,
    ) = if (name.equals(AUTHORIZATION, ignoreCase = true)) DigestAuth.credentials(value) else null

    private fun headers(
        written: List<Pair<String, String>>,
        notes: MutableList<String>,
    ): List<Pair<String, String>> {
        val headers =
            written.mapNotNull { (name, value) ->
                when {
                    name.lowercase() in RESTRICTED -> {
                        notes += "$name is set by the HTTP client; the written header was not sent"
                        null
                    }

                    // Digest credentials are only sent as the answer to a challenge (see RequestExecutor).
                    digestCredentials(name, value) != null -> {
                        null
                    }

                    name.equals(AUTHORIZATION, ignoreCase = true) -> {
                        name to BasicAuth.normalize(value)
                    }

                    else -> {
                        name to value
                    }
                }
            }
        val defaults = DEFAULTS.filter { (name, _) -> headers.none { it.first.equals(name, ignoreCase = true) } }
        return headers + defaults
    }

    /** The body parts laid out by content type; [resolve] gives null for texts with missing variables. */
    private inner class Body(
        private val parts: List<BodyPart>,
        private val resolve: (String) -> String?,
        private val notes: MutableList<String>,
    ) {
        fun bytes(contentType: String): ByteArray {
            val type = contentType.substringBefore(';').trim().lowercase()
            return when {
                parts.isEmpty() -> ByteArray(0)
                type.startsWith("multipart/") -> multipart()
                type == "application/x-www-form-urlencoded" -> form()
                else -> plain()
            }
        }

        private fun plain(): ByteArray =
            joined(
                parts.map { part ->
                    when (part) {
                        is BodyPart.Text -> text(part.text)
                        is BodyPart.FileRef -> file(part, substitute = true)
                    }
                },
                "\n",
            )

        /** Lines in CRLF framing; files are inserted unchanged where their `< path` line is. */
        private fun multipart(): ByteArray =
            joined(
                parts.flatMap { part ->
                    when (part) {
                        is BodyPart.Text -> (resolve(part.text) ?: part.text).split('\n').map { it.toByteArray() }
                        is BodyPart.FileRef -> listOf(file(part, substitute = false))
                    }
                },
                "\r\n",
            )

        /** Sent as written, except that line breaks before lines starting with `&` (or after lines ending with it) go. */
        private fun form(): ByteArray {
            val text = String(plain(), Charsets.UTF_8)
            val result = StringBuilder()
            for (line in text.split('\n')) {
                val continued = result.isEmpty() || result.endsWith("&") || line.trimStart().startsWith("&")
                if (result.isNotEmpty() && !continued) result.append('\n')
                result.append(if (continued) line.trim() else line)
            }
            return result.toString().toByteArray()
        }

        private fun text(text: String) = (resolve(text) ?: text).toByteArray()

        private fun file(
            ref: BodyPart.FileRef,
            substitute: Boolean,
        ): ByteArray {
            val path = resolve(ref.path) ?: return ByteArray(0)
            val file =
                httpFile
                    .toAbsolutePath()
                    .parent
                    .resolve(path)
                    .normalize()
            if (!file.isRegularFile()) {
                throw RequestBuildException("Body file not found: $path (line ${ref.line + 1})")
            }
            val bytes =
                try {
                    file.readBytes()
                } catch (e: IOException) {
                    throw RequestBuildException("Body file $path cannot be read (line ${ref.line + 1}): ${e.message}")
                }
            if (!substitute) return bytes
            val text = utf8(bytes) ?: return bytes
            // Files may contain braces of their own, so their unresolved variables do not stop the request.
            val partial = resolver.resolvePartially(text)
            if (partial.unresolved.isNotEmpty()) {
                notes += "Unresolved variables in $path: ${partial.unresolved.joinToString(", ")}; sent as written"
            }
            return partial.text.toByteArray()
        }

        private fun joined(
            items: List<ByteArray>,
            separator: String,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            items.forEachIndexed { index, bytes ->
                if (index > 0) out.write(separator.toByteArray())
                out.write(bytes)
            }
            return out.toByteArray()
        }
    }

    private companion object {
        const val CONTENT_TYPE = "Content-Type"
        const val AUTHORIZATION = "Authorization"

        /** Headers the JDK client sets itself and refuses; `Host` is allowed (see [HttpClients]). */
        val RESTRICTED =
            setOf(
                "content-length",
                "connection",
                "expect",
                "upgrade",
                "transfer-encoding",
                "te",
                "trailer",
                "keep-alive",
                "proxy-connection",
            )

        val DEFAULTS = listOf("User-Agent" to "SluIde", "Accept" to "*/*", "Accept-Encoding" to "gzip, deflate")

        /** [bytes] as text if they are valid UTF-8 without NUL characters, else null. */
        fun utf8(bytes: ByteArray): String? =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
                    .takeIf { '\u0000' !in it }
            } catch (_: CharacterCodingException) {
                null
            }
    }
}
