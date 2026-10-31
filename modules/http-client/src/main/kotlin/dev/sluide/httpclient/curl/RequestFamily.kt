package dev.sluide.httpclient.curl

import dev.sluide.converting.ConversionOption
import dev.sluide.converting.FormatFamily
import dev.sluide.converting.FormatMatch
import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.TextFormat
import dev.sluide.converting.WriteResult
import dev.sluide.fileviewers.SyntaxHighlighter
import dev.sluide.httpclient.ui.HttpSyntaxHighlighter
import org.springframework.stereotype.Component

/** `curl` commands for a POSIX shell, Windows cmd or PowerShell. */
internal object CurlFormat : TextFormat {
    override val id = "curl"
    override val name = "cURL"
    override val language = "bash"
    override val extensions = listOf("sh")
}

/** Requests of the HTTP client's `.http` files. */
internal object HttpRequestFormat : TextFormat {
    override val id = "http"
    override val name = "HTTP request"
    override val language = "http"
    override val extensions = listOf("http", "rest")

    /** The `.http` editor's own highlighter, which `Highlighters.forLanguage` does not know. */
    override val highlighter: SyntaxHighlighter = HttpSyntaxHighlighter()
}

/**
 * The requests family the HTTP client contributes to the Converting tool (ADR 0115, 0116): `curl` commands and `.http`
 * requests, converted through [CurlRequest]s. `{{variables}}` stay as written.
 */
@Component
internal class RequestFamily : FormatFamily<List<CurlRequest>> {
    override val name = "Requests"
    override val formats: List<TextFormat> = listOf(CurlFormat, HttpRequestFormat)

    override fun detect(text: String): List<FormatMatch> =
        guarded({ emptyList() }) {
            when {
                text.isBlank() -> emptyList()
                CurlTokenizer.looksLikeCurl(text) -> listOf(FormatMatch(CurlFormat, CURL_SCORE))
                HttpRequestReader.detects(text) -> listOf(FormatMatch(HttpRequestFormat, HTTP_SCORE))
                else -> emptyList()
            }
        }

    override fun readOptions(format: TextFormat): List<ConversionOption> {
        check(format)
        return emptyList()
    }

    override fun writeOptions(format: TextFormat): List<ConversionOption> =
        if (check(format) == CurlFormat) listOf(SHELL) else emptyList()

    override fun read(
        text: String,
        format: TextFormat,
        options: OptionValues,
    ): ReadResult<List<CurlRequest>> {
        val checked = check(format)
        if (text.isBlank()) return ReadResult.Read(emptyList(), emptyList())
        return guarded({ ReadResult.Failed(it, null) }) {
            if (checked == CurlFormat) CurlReader.read(text) else HttpRequestReader.read(text)
        }
    }

    override fun write(
        content: List<CurlRequest>,
        format: TextFormat,
        options: OptionValues,
    ): WriteResult {
        val checked = check(format)
        if (content.isEmpty()) return WriteResult.Written("", emptyList())
        return guarded({ WriteResult.Failed(it) }) {
            if (checked == CurlFormat) {
                CurlWriter.write(content, Shell.ofLabel(options.choice(SHELL)))
            } else {
                HttpRequestWriter.write(content)
            }
        }
    }

    private fun check(format: TextFormat): TextFormat =
        requireNotNull(
            formats.firstOrNull { it.id == format.id },
        ) { "${format.id} is not a format of the $name family" }

    /** Families never throw: an unforeseen exception becomes [failed] with its message. */
    private inline fun <R> guarded(
        failed: (String) -> R,
        call: () -> R,
    ): R =
        try {
            call()
        } catch (e: RuntimeException) {
            failed(e.message ?: e::class.simpleName.orEmpty())
        }

    companion object {
        val SHELL = ConversionOption.Choice("shell", "Shell", Shell.entries.map { it.label }, Shell.POSIX.label)
        const val CURL_SCORE = 100
        const val HTTP_SCORE = 90
    }
}
