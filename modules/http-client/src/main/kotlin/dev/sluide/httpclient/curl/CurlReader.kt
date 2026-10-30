package dev.sluide.httpclient.curl

import dev.sluide.converting.ReadResult
import dev.sluide.httpclient.parse.HttpVersion
import java.math.BigDecimal
import java.net.URLEncoder
import java.time.Duration

/**
 * Reads `curl` commands into [CurlRequest]s (ADR 0116): the request options curl and the HTTP client share are
 * mapped, output options (`-s`, `-o`, `-v`, …) dropped and other options named in a note.
 */
internal object CurlReader {
    fun read(text: String): ReadResult<List<CurlRequest>> {
        val commands =
            try {
                CurlTokenizer.tokenize(text)
            } catch (e: CurlSyntaxException) {
                return ReadResult.Failed(e.message ?: "Invalid command", e.offset)
            }
        val notes = mutableListOf<String>()
        val ignored = linkedSetOf<String>()
        val requests =
            commands.map { command ->
                val program = command.words.first()
                if (!program.text.equals("curl", ignoreCase = true) && !program.text.equals("curl.exe", true)) {
                    return ReadResult.Failed("Expected a curl command, not ${program.text}", program.offset)
                }
                command.ignored?.let { notes += "Ignored after the command: $it" }
                try {
                    Command(command, notes, ignored).request()
                } catch (e: CurlSyntaxException) {
                    return ReadResult.Failed(e.message ?: "Invalid command", e.offset)
                }
            }
        if (ignored.isNotEmpty()) notes += "Ignored: ${ignored.joinToString(", ")}"
        return ReadResult.Read(requests, notes)
    }

    /** The options of one command, read in order. */
    private class Command(
        private val command: ShellCommand,
        private val notes: MutableList<String>,
        private val ignored: MutableSet<String>,
    ) {
        private var method: String? = null
        private var head = false
        private var get = false
        private var upload: String? = null
        private val urls = mutableListOf<String>()
        private val headers = mutableListOf<Pair<String, String>>()
        private val data = mutableListOf<DataPiece>()
        private var json = false
        private val form = mutableListOf<FormPart>()
        private var formAt = -1
        private var dataAt = -1
        private var version: HttpVersion? = null
        private var follow = false
        private var timeout: Duration? = null
        private var connectTimeout: Duration? = null
        private var insecure = false
        private var user: String? = null
        private var userAt = 0
        private var digest = false

        fun request(): CurlRequest {
            val args = command.words.drop(1)
            var index = 0
            while (index < args.size) {
                val word = args[index]
                val text = word.text

                /** The next word, the value of [option]. */
                fun value(option: String): Word =
                    args.getOrNull(++index) ?: throw CurlSyntaxException("$option needs a value", word.offset)
                when {
                    text.startsWith("--") && text.length > 2 -> {
                        option(text, word, if (text in CurlOptions.LONG_WITH_VALUE) value(text) else null)
                    }

                    text.startsWith("-") && text.length > 1 -> {
                        var at = 1
                        while (at < text.length) {
                            val short = text[at]
                            if (short in CurlOptions.SHORT_WITH_VALUE) {
                                val attached = text.substring(at + 1)
                                val value = if (attached.isEmpty()) value("-$short") else Word(attached, word.offset)
                                option(CurlOptions.SHORT[short] ?: "-$short", word, value)
                                break
                            }
                            option(CurlOptions.SHORT[short] ?: "-$short", word, null)
                            at++
                        }
                    }

                    else -> {
                        urls += text
                    }
                }
                index++
            }
            return build()
        }

        private fun option(
            name: String,
            word: Word,
            value: Word?,
        ) {
            val text = value?.text.orEmpty()
            when (name) {
                "--request" -> {
                    method = text
                }

                "--header" -> {
                    header(text)
                }

                "--data", "--data-ascii" -> {
                    data(dataPiece(text, binary = false), word)
                }

                "--data-binary" -> {
                    data(dataPiece(text, binary = true), word)
                }

                "--data-raw" -> {
                    data(DataPiece.Text(text), word)
                }

                "--data-urlencode" -> {
                    data(urlEncoded(text), word)
                }

                "--json" -> {
                    json(text, word)
                }

                "--form" -> {
                    formPart(text, word, plain = false)
                }

                "--form-string" -> {
                    formPart(text, word, plain = true)
                }

                "--user" -> {
                    user = text.also { userAt = value?.offset ?: word.offset }
                }

                "--digest" -> {
                    digest = true
                }

                "--basic" -> {
                    digest = false
                }

                "--oauth2-bearer" -> {
                    headers += "Authorization" to "Bearer $text"
                }

                "--cookie" -> {
                    cookie(text)
                }

                "--user-agent" -> {
                    headers += "User-Agent" to text
                }

                "--referer" -> {
                    headers += "Referer" to text.removeSuffix(";auto")
                }

                "--url" -> {
                    urls += text
                }

                "--get" -> {
                    get = true
                }

                "--head" -> {
                    head = true
                }

                "--upload-file" -> {
                    upload = text
                }

                "--location", "--location-trusted" -> {
                    follow = true
                }

                "--no-location" -> {
                    follow = false
                }

                "--insecure" -> {
                    insecure = true
                }

                "--http1.1" -> {
                    version = HttpVersion.HTTP_1_1
                }

                "--http2", "--http2-prior-knowledge" -> {
                    version = HttpVersion.HTTP_2
                }

                "--max-time" -> {
                    timeout = seconds(name, value)
                }

                "--connect-timeout" -> {
                    connectTimeout = seconds(name, value)
                }

                in CurlOptions.DROPPED -> {}

                else -> {
                    ignored += name
                }
            }
        }

        private fun header(text: String) {
            when {
                text.startsWith("@") -> {
                    ignored += "-H $text"
                }

                text.endsWith(";") && ':' !in text -> {
                    headers += text.removeSuffix(";").trim() to ""
                }

                ':' !in text -> {
                    ignored += "-H $text"
                }

                text.substringAfter(':').isBlank() -> {
                    // curl removes the header it would send; there is none to remove here.
                    notes += "Header ${text.substringBefore(':').trim()} removed by curl; left out"
                }

                else -> {
                    headers += text.substringBefore(':').trim() to text.substringAfter(':').trim()
                }
            }
        }

        private fun cookie(text: String) {
            if ('=' !in text) {
                notes += "Cookie file $text ignored"
                return
            }
            val existing = headers.indexOfLast { it.first.equals("Cookie", ignoreCase = true) }
            if (existing < 0) {
                headers += "Cookie" to text
            } else {
                headers[existing] = "Cookie" to "${headers[existing].second}; $text"
            }
        }

        private fun dataPiece(
            text: String,
            binary: Boolean,
        ): DataPiece =
            if (text.startsWith("@") &&
                text.length > 1
            ) {
                DataPiece.File(text.substring(1), binary)
            } else {
                DataPiece.Text(text)
            }

        private fun data(
            piece: DataPiece,
            word: Word,
        ) {
            if ((piece as? DataPiece.File)?.path == "-") {
                notes += "Data from standard input dropped"
                return
            }
            if (dataAt < 0) dataAt = word.offset
            val last = data.lastOrNull()
            // curl joins several -d with &.
            if (piece is DataPiece.Text && last is DataPiece.Text) {
                data[data.lastIndex] = DataPiece.Text(last.text + "&" + piece.text)
            } else {
                data += piece
            }
        }

        /** `--data-urlencode`: `content`, `=content`, `name=content`, `@file` or `name@file`. */
        private fun urlEncoded(text: String): DataPiece {
            val equals = text.indexOf('=')
            val at = text.indexOf('@')
            return when {
                at >= 0 && (equals < 0 || at < equals) -> {
                    val name =
                        text
                            .substring(0, at)
                            .takeIf { it.isNotEmpty() }
                            ?.let { ", without $it=" }
                            .orEmpty()
                    notes += "--data-urlencode $text: the file is sent as it is$name, not URL-encoded"
                    DataPiece.File(text.substring(at + 1))
                }

                equals == 0 -> {
                    DataPiece.Text(encode(text.substring(1)))
                }

                equals > 0 -> {
                    DataPiece.Text(text.substring(0, equals) + "=" + encode(text.substring(equals + 1)))
                }

                else -> {
                    DataPiece.Text(encode(text))
                }
            }
        }

        private fun json(
            text: String,
            word: Word,
        ) {
            json = true
            val piece = dataPiece(text, binary = true)
            val last = data.lastOrNull()
            // Several --json are joined without a separator.
            if (piece is DataPiece.Text && last is DataPiece.Text) {
                data[data.lastIndex] = DataPiece.Text(last.text + piece.text)
            } else {
                data(piece, word)
            }
        }

        /** `name=value`, `name=@file`, `name=<file`, each with `;type=` and (files) `;filename=`. */
        private fun formPart(
            text: String,
            word: Word,
            plain: Boolean,
        ) {
            if (formAt < 0) formAt = word.offset
            val equals = text.indexOf('=')
            if (equals <= 0) throw CurlSyntaxException("-F needs name=content", word.offset)
            val name = text.substring(0, equals)
            val content = text.substring(equals + 1)
            if (plain) {
                form += FormPart(name, FormContent.Value(content))
                return
            }
            val first = content.substringBefore(';')
            val attributes =
                content
                    .split(';')
                    .drop(1)
                    .associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=', "").trim('"') }
            form +=
                when {
                    first.startsWith("@") -> {
                        FormPart(name, FormContent.File(first.substring(1), attributes["filename"]), attributes["type"])
                    }

                    first.startsWith("<") -> {
                        FormPart(name, FormContent.FileText(first.substring(1)), attributes["type"])
                    }

                    // A value keeps its semicolons unless they start type=.
                    else -> {
                        FormPart(name, FormContent.Value(content.substringBefore(";type=")), type(content))
                    }
                }
        }

        private fun type(content: String) = content.substringAfter(";type=", "").ifEmpty { null }

        private fun seconds(
            option: String,
            value: Word?,
        ): Duration? {
            val number = value?.text?.toBigDecimalOrNull()
            if (number == null || number.signum() <= 0) {
                throw CurlSyntaxException("$option needs a number of seconds", value?.offset ?: 0)
            }
            if (number > MAX_SECONDS) {
                throw CurlSyntaxException(
                    "$option takes at most ${MAX_SECONDS.toPlainString()} seconds",
                    value?.offset ?: 0,
                )
            }
            return Duration.ofMillis(number.multiply(BigDecimal(1000)).toLong().coerceAtLeast(1))
        }

        private fun build(): CurlRequest {
            if (urls.isEmpty()) throw CurlSyntaxException("The command has no URL", command.offset)
            if (urls.size > 1) notes += "Further URLs ignored: ${urls.drop(1).joinToString(" ")}"
            if (form.isNotEmpty() && data.isNotEmpty()) {
                throw CurlSyntaxException("-F cannot be combined with -d or --json", maxOf(formAt, dataAt))
            }
            var url = urls.first()
            val body =
                when {
                    form.isNotEmpty() -> RequestBody.Multipart(form)
                    upload != null -> RequestBody.Data(listOf(DataPiece.File(upload.orEmpty())))
                    data.isEmpty() -> RequestBody.None
                    get -> RequestBody.None.also { url = withQuery(url) }
                    else -> RequestBody.Data(data)
                }
            if (json) {
                if (headers.valueOf(CONTENT_TYPE) == null) headers += CONTENT_TYPE to "application/json"
                if (headers.valueOf("Accept") == null) headers += "Accept" to "application/json"
            } else if (body is RequestBody.Data && upload == null && headers.valueOf(CONTENT_TYPE) == null) {
                headers += CONTENT_TYPE to FORM_TYPE
            }
            return CurlRequest(
                url = url,
                method = method ?: defaultMethod(body),
                headers = headers,
                body = body,
                version = version,
                followRedirects = follow,
                timeout = timeout,
                connectTimeout = connectTimeout,
                insecure = insecure,
                credentials = credentials(),
                name = command.comment?.ifBlank { null },
            )
        }

        private fun defaultMethod(body: RequestBody) =
            when {
                head -> "HEAD"
                upload != null -> "PUT"
                body != RequestBody.None -> "POST"
                else -> "GET"
            }

        /** `-G`: the data as the URL's query. */
        private fun withQuery(url: String): String {
            val files = data.filterIsInstance<DataPiece.File>()
            if (files.isNotEmpty()) {
                notes +=
                    "-G: data files cannot go into the URL; dropped: ${files.joinToString { it.path }}"
            }
            val query = data.filterIsInstance<DataPiece.Text>().joinToString("&") { it.text }
            if (query.isEmpty()) return url
            return url + (if ('?' in url) "&" else "?") + query
        }

        private fun credentials(): Credentials? {
            val user = user ?: return null
            if (':' !in user) notes += "-u $user has no password; curl asks for it, the request gets an empty one"
            if (user.isEmpty()) throw CurlSyntaxException("-u needs user:password", userAt)
            return Credentials(user.substringBefore(':'), user.substringAfter(':', ""), digest)
        }
    }

    /** The longest timeout, about 31 years: more is surely a mistake, and would overflow as milliseconds. */
    private val MAX_SECONDS = BigDecimal(1_000_000_000)

    /** URL-encoded as curl does: `%20` for spaces, `-._~` and alphanumerics as they are. */
    internal fun encode(text: String): String =
        URLEncoder
            .encode(text, Charsets.UTF_8)
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
}
