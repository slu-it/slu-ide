package dev.sluide.httpclient.curl

import dev.sluide.converting.WriteResult
import dev.sluide.httpclient.parse.HttpVersion
import java.math.BigDecimal
import java.time.Duration

/**
 * Writes [CurlRequest]s as `curl` commands for a [Shell] (ADR 0116): one option per line with the shell's line
 * continuation, quoted only where needed, commands separated by a blank line; a request's name is a comment line
 * before its command.
 */
internal object CurlWriter {
    fun write(
        requests: List<CurlRequest>,
        shell: Shell,
    ): WriteResult {
        val notes = linkedSetOf<String>()
        val commands = requests.map { command(it, shell, notes) }
        if (shell == Shell.POWERSHELL && commands.any { '"' in it }) {
            notes +=
                "Windows PowerShell 5.1 drops double quotes in the arguments of curl.exe; PowerShell 7.3 keeps them"
        }
        return WriteResult.Written(commands.joinToString("\n\n", postfix = "\n"), notes.toList())
    }

    private fun command(
        request: CurlRequest,
        shell: Shell,
        notes: MutableSet<String>,
    ): String {
        val lines = mutableListOf<List<String>>()
        val first = mutableListOf(if (shell == Shell.POWERSHELL) "curl.exe" else "curl")
        val defaultMethod = if (request.body == RequestBody.None) "GET" else "POST"
        when {
            request.method == "HEAD" && request.body == RequestBody.None -> first += "-I"
            request.method != defaultMethod -> first += listOf("-X", request.method)
        }
        first += request.url
        lines += first
        if (request.followRedirects) lines += listOf("-L")
        when (request.version) {
            HttpVersion.HTTP_2 -> {
                lines += listOf("--http2")
            }

            HttpVersion.HTTP_1_1 -> {
                lines += listOf("--http1.1")
            }

            null -> {}
        }
        request.timeout?.let { lines += listOf("-m", seconds(it)) }
        request.connectTimeout?.let { lines += listOf("--connect-timeout", seconds(it)) }
        if (request.insecure) lines += listOf("-k")
        request.credentials?.let {
            if (it.digest) lines += listOf("--digest")
            lines += listOf("-u", "${it.user}:${it.password}")
        }
        val headers =
            if (request.body is RequestBody.Data &&
                request.headers.valueOf(CONTENT_TYPE).equals(FORM_TYPE, ignoreCase = true)
            ) {
                // curl's own for -d.
                request.headers.without(CONTENT_TYPE)
            } else {
                request.headers
            }
        for ((name, value) in headers) lines += listOf("-H", if (value.isEmpty()) "$name;" else "$name: $value")
        lines += body(request.body, notes)
        val comment = request.name?.let { (if (shell == Shell.CMD) "REM " else "# ") + it.replace('\n', ' ') + "\n" }
        return comment.orEmpty() +
            lines.joinToString(" ${continuation(shell)}\n  ") { line -> line.joinToString(" ") { quote(it, shell) } }
    }

    private fun body(
        body: RequestBody,
        notes: MutableSet<String>,
    ): List<List<String>> =
        when (body) {
            RequestBody.None -> {
                emptyList()
            }

            is RequestBody.Data -> {
                body.pieces.map { piece ->
                    when (piece) {
                        is DataPiece.Text -> listOf(if (piece.text.startsWith("@")) "--data-raw" else "-d", piece.text)
                        is DataPiece.File -> listOf(if (piece.binary) "--data-binary" else "-d", "@${piece.path}")
                    }
                }
            }

            is RequestBody.Multipart -> {
                body.parts.map { formOption(it, notes) }
            }
        }

    private fun formOption(
        part: FormPart,
        notes: MutableSet<String>,
    ): List<String> {
        val type = part.type?.let { ";type=$it" }.orEmpty()
        return when (val content = part.content) {
            is FormContent.Value -> {
                // -F would read these as a file or as attributes.
                val plain = content.text.startsWith("@") || content.text.startsWith("<") || ';' in content.text
                if (plain) {
                    if (part.type != null) {
                        notes += "The type of field ${part.name} is left out: --form-string cannot carry one"
                    }
                    listOf("--form-string", "${part.name}=${content.text}")
                } else {
                    listOf("-F", "${part.name}=${content.text}$type")
                }
            }

            is FormContent.File -> {
                listOf("-F", "${part.name}=@${content.path}$type" + content.filename?.let { ";filename=$it" }.orEmpty())
            }

            is FormContent.FileText -> {
                listOf("-F", "${part.name}=<${content.path}$type")
            }
        }
    }

    private fun seconds(duration: Duration): String =
        BigDecimal.valueOf(duration.toMillis(), 3).stripTrailingZeros().toPlainString()

    private fun continuation(shell: Shell) =
        when (shell) {
            Shell.POSIX -> "\\"
            Shell.CMD -> "^"
            Shell.POWERSHELL -> "`"
        }

    /** [word] as one argument of [shell], quoted only if it has to be. */
    internal fun quote(
        word: String,
        shell: Shell,
    ): String =
        when (shell) {
            // zsh expands a word starting with = to a command's path.
            Shell.POSIX -> {
                if (word.all { it in POSIX_SAFE } &&
                    word.firstOrNull()?.equals('=') == false
                ) {
                    word
                } else {
                    posix(word)
                }
            }

            Shell.POWERSHELL -> {
                if (word.isNotEmpty() && word.all { it in POWERSHELL_SAFE }) word else powerShell(word)
            }

            Shell.CMD -> {
                cmd(word)
            }
        }

    private fun posix(word: String) = "'" + word.replace("'", "'\\''") + "'"

    /** PowerShell also ends single quotes at the typographic ones, so each is doubled as well. */
    private fun powerShell(word: String) =
        "'" +
            word.fold(StringBuilder()) { result, char ->
                result.append(char)
                if (char in CurlTokenizer.POWERSHELL_SINGLE) result.append(char)
                result
            } + "'"

    /**
     * cmd hands the command line to curl, whose C runtime splits it: plain words as they are, words without `"`, `%`,
     * `!` and line breaks in `"…"`; others in `^"…^"`, where cmd sees no quotes, so its special characters get `^`.
     */
    private fun cmd(word: String): String {
        if (word.isNotEmpty() && word.all { it in CMD_SAFE }) return word
        if (word.none { it in "\"%!\n" }) return "\"" + doubledTrailingBackslashes(word) + "\""
        val result = StringBuilder("^\"")
        var backslashes = 0
        for (char in word) {
            when (char) {
                '\\' -> {
                    backslashes++
                    result.append(char)
                    continue
                }

                // The C runtime: backslashes before a quote are doubled, the quote escaped.
                '"' -> {
                    result.append("\\".repeat(backslashes)).append("\\^\"")
                }

                '\n' -> {
                    result.append("^\n\n")
                }

                in CMD_SPECIAL -> {
                    result.append('^').append(char)
                }

                else -> {
                    result.append(char)
                }
            }
            backslashes = 0
        }
        result.append("\\".repeat(backslashes))
        return result.append("^\"").toString()
    }

    /** Backslashes before the closing quote would escape it. */
    private fun doubledTrailingBackslashes(word: String): String {
        val trailing = word.length - word.trimEnd('\\').length
        return word + "\\".repeat(trailing)
    }

    private val ALPHANUMERIC = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    private val POSIX_SAFE = (ALPHANUMERIC + "_@%+=:,./-".toList()).toSet()
    private val POWERSHELL_SAFE = (ALPHANUMERIC + "_+=:./-".toList()).toSet()
    private val CMD_SAFE = (ALPHANUMERIC + "_@+:./-".toList()).toSet()
    private const val CMD_SPECIAL = "^&|<>()%!"
}
