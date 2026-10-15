package dev.sluide.httpclient.ui

import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import tools.jackson.core.JacksonException
import tools.jackson.core.JsonToken
import tools.jackson.core.exc.StreamConstraintsException
import tools.jackson.core.util.DefaultIndenter
import tools.jackson.core.util.DefaultPrettyPrinter
import tools.jackson.core.util.Separators
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException
import java.time.Duration
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * A response body prepared for display.
 *
 * [text] is what to show: pretty-printed JSON or XML where possible, otherwise the body as received; for a binary
 * body a summary. [raw] is the body as received, decoded, or `null` if it is binary. [language] is the one to
 * highlight [text] with ([MediaTypes.language]). [notes] say how the body was decoded or why it was not
 * pretty-printed.
 */
internal data class FormattedBody(
    val text: String,
    val raw: String?,
    val language: String?,
    val notes: List<String> = emptyList(),
) {
    val binary: Boolean get() = raw == null
}

/** Formats response bodies and the numbers of the status line for display. Thread-safe. */
internal object ResponseFormatter {
    /** Larger bodies are shown as received: pretty-printing them would hold up the view. */
    const val PRETTY_PRINT_LIMIT = 1024 * 1024

    /** The fallback for text without a declared charset that is not UTF-8, as browsers do. */
    val FALLBACK_CHARSET: Charset = Charset.forName("windows-1252")

    private val mapper = JsonMapper.builder().build()

    private val prettyPrinter =
        DefaultPrettyPrinter()
            .withSeparators(
                Separators
                    .createDefaultInstance()
                    .withObjectNameValueSpacing(Separators.Spacing.AFTER)
                    .withObjectEmptySeparator("")
                    .withArrayEmptySeparator(""),
            ).withObjectIndenter(DefaultIndenter("  ", "\n"))
            .withArrayIndenter(DefaultIndenter("  ", "\n"))

    /**
     * Decodes [body] and pretty-prints JSON and XML (unless not [pretty]).
     *
     * The charset is the one of a byte order mark (UTF-8, UTF-16LE/BE), else the one declared in [contentType], else
     * UTF-8. Text types (`text/…`, JSON, XML, HTML) without a declared charset that are not UTF-8 fall back to
     * [FALLBACK_CHARSET]. A body that does not decode, or contains NUL characters, is binary.
     */
    fun format(
        body: ByteArray,
        contentType: String?,
        pretty: Boolean = true,
    ): FormattedBody {
        val mediaType = MediaTypes.mediaType(contentType)
        val language = MediaTypes.language(contentType)
        val decoded = decode(body, contentType, isText = mediaType.startsWith("text/") || language != null)
        if (decoded == null || '\u0000' in decoded.text) {
            val type = if (mediaType.isEmpty()) "" else ", $mediaType"
            return FormattedBody("Binary body, ${size(body.size.toLong())}$type", raw = null, language = null)
        }
        val text = decoded.text
        val notes = listOfNotNull(decoded.note)
        val formattable = language == "json" || language == "xml"
        if (!pretty || !formattable || text.isBlank()) return FormattedBody(text, text, language, notes)
        if (body.size > PRETTY_PRINT_LIMIT) {
            val note = "Not pretty-printed: larger than ${size(PRETTY_PRINT_LIMIT.toLong())}"
            return FormattedBody(text, text, language, notes + note)
        }
        return when (val pretty = if (language == "json") prettyJson(text) else prettyXml(text)) {
            is Pretty.Formatted -> FormattedBody(pretty.text, text, language, notes)
            is Pretty.Failed -> FormattedBody(text, text, language, notes + pretty.note)
        }
    }

    /** The charset parameter of [contentType], or `null` if there is none or it is unknown. */
    fun declaredCharset(contentType: String?): Charset? {
        val name =
            contentType
                .orEmpty()
                .split(';')
                .drop(1)
                .map { it.trim() }
                .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
                ?.substringAfter('=')
                ?.trim()
                ?.removeSurrounding("\"")
                ?: return null
        return try {
            Charset.forName(name)
        } catch (_: IllegalCharsetNameException) {
            null
        } catch (_: UnsupportedCharsetException) {
            null
        }
    }

    /** "512 B", "12.3 KB", "4.0 MB" (units of 1024). */
    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unit = 0
        // 1023.95 KB would round to "1024.0 KB", so it is shown as 1.0 MB.
        while (value >= 1023.95 && unit < UNITS.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit])
    }

    /** "850 ms", "1.25 s", "2 min 5 s". */
    fun duration(duration: Duration): String {
        val millis = duration.toMillis()
        // 59 995 ms would round to "60.00 s", so it is shown in minutes.
        if (millis < 1000) return "$millis ms"
        if (millis < 59_995) return String.format(Locale.ROOT, "%.2f s", millis / 1000.0)
        val seconds = (millis + 500) / 1000
        return "${seconds / 60} min ${seconds % 60} s"
    }

    private val UNITS = listOf("B", "KB", "MB", "GB", "TB")

    private enum class ByteOrderMark(
        val charset: Charset,
        vararg bytes: Int,
    ) {
        UTF_8(Charsets.UTF_8, 0xEF, 0xBB, 0xBF),
        UTF_16BE(Charsets.UTF_16BE, 0xFE, 0xFF),
        UTF_16LE(Charsets.UTF_16LE, 0xFF, 0xFE),
        ;

        val bytes = bytes.map { it.toByte() }

        fun startsBody(body: ByteArray) = body.size >= bytes.size && bytes.indices.all { body[it] == bytes[it] }
    }

    private class Decoded(
        val text: String,
        val note: String? = null,
    )

    /** [body] as text without its byte order mark, or `null` if it does not decode (see [format]). */
    private fun decode(
        body: ByteArray,
        contentType: String?,
        isText: Boolean,
    ): Decoded? {
        val mark = ByteOrderMark.entries.firstOrNull { it.startsBody(body) }
        val declared = declaredCharset(contentType)
        val start = mark?.bytes?.size ?: 0
        try {
            val text =
                (mark?.charset ?: declared ?: Charsets.UTF_8)
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body, start, body.size - start))
                    .toString()
            return Decoded(text)
        } catch (_: CharacterCodingException) {
            if (mark != null || declared != null || !isText) return null
            return Decoded(String(body, FALLBACK_CHARSET), "Not UTF-8 and no charset declared: decoded as windows-1252")
        }
    }

    private sealed interface Pretty {
        class Formatted(
            val text: String,
        ) : Pretty

        class Failed(
            val note: String,
        ) : Pretty
    }

    private fun invalid(language: String) = Pretty.Failed("Not valid $language: shown as received")

    /** Beyond the limits of the parsers (Jackson's stream constraints, the JDK's `jdk.xml.*` limits). */
    private val tooComplex = Pretty.Failed("Not pretty-printed: too deeply nested or too large to format")

    /**
     * [text] re-indented, token by token, so keys keep their order and numbers their exact spelling; failed if it is
     * not a single valid JSON value or exceeds the parser's limits (nesting depth, length of numbers and strings).
     */
    private fun prettyJson(text: String): Pretty =
        try {
            val writer = StringWriter()
            mapper.createParser(text).use { parser ->
                mapper.writer().with(prettyPrinter).createGenerator(writer).use { generator ->
                    var depth = 0
                    var roots = 0
                    var token = parser.nextToken()
                    while (token != null) {
                        when (token) {
                            // As written: copying would turn 2e3 into 2E+3.
                            JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT -> {
                                generator.writeNumber(parser.text)
                            }

                            else -> {
                                generator.copyCurrentEvent(parser)
                            }
                        }
                        if (token.isStructStart) depth++
                        if (token.isStructEnd) depth--
                        if (depth == 0 && ++roots > 1) return invalid("JSON")
                        token = parser.nextToken()
                    }
                }
            }
            if (writer.buffer.isEmpty()) invalid("JSON") else Pretty.Formatted(writer.toString())
        } catch (_: StreamConstraintsException) {
            tooComplex
        } catch (_: JacksonException) {
            invalid("JSON")
        }

    /**
     * [text] re-indented, or failed if it is not well-formed XML or exceeds the JDK's limits. Documents with a DOCTYPE are refused, so no DTD and
     * no external entity is ever loaded. An XML declaration is kept as written.
     */
    private fun prettyXml(text: String): Pretty =
        try {
            val factory =
                DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    isExpandEntityReferences = false
                    isXIncludeAware = false
                    setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    // Refusing DOCTYPEs already keeps DTDs out; these only add to it where supported.
                    trySetAttribute { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
                    trySetAttribute { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
                }
            val builder = factory.newDocumentBuilder().apply { setErrorHandler(FailingErrorHandler) }
            val document = builder.parse(InputSource(StringReader(text)))
            removeBlankText(document)
            val transformer =
                TransformerFactory
                    .newInstance()
                    .apply {
                        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                        trySetAttribute { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
                        trySetAttribute { setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "") }
                    }.newTransformer()
                    .apply {
                        setOutputProperty(OutputKeys.INDENT, "yes")
                        setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
                        setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
                    }
            val writer = StringWriter()
            transformer.transform(DOMSource(document), StreamResult(writer))
            val declaration =
                XML_DECLARATION
                    .find(text)
                    ?.value
                    ?.trim()
                    ?.let { "$it\n" }
                    .orEmpty()
            Pretty.Formatted(declaration + writer.toString().replace("\r\n", "\n").trimEnd() + "\n")
        } catch (exception: SAXException) {
            // The JDK's processing limits (e.g. an element depth of 100) report codes like JAXP00010006.
            if (exception.message.orEmpty().startsWith("JAXP0001")) tooComplex else invalid("XML")
        } catch (_: IOException) {
            invalid("XML")
        } catch (_: ParserConfigurationException) {
            invalid("XML")
        } catch (_: TransformerException) {
            invalid("XML")
        }

    /** Factories reject attributes they do not know with an [IllegalArgumentException]. */
    private inline fun trySetAttribute(set: () -> Unit) {
        try {
            set()
        } catch (_: IllegalArgumentException) {
        }
    }

    /** Whitespace between elements, which the indenting transformer would keep in addition to its own. */
    private fun removeBlankText(root: Node) {
        val nodes = ArrayDeque(listOf(root))
        while (nodes.isNotEmpty()) {
            val node = nodes.removeLast()
            var child = node.firstChild
            while (child != null) {
                val next = child.nextSibling
                if (child.nodeType == Node.TEXT_NODE && child.nodeValue.isBlank()) {
                    node.removeChild(child)
                } else {
                    nodes += child
                }
                child = next
            }
        }
    }

    /** `<?xml version="1.0"?>`, not a processing instruction like `<?xml-stylesheet …?>`. */
    private val XML_DECLARATION = Regex("""^\s*<\?xml\s[^?]*\?>""")

    /** Fails on errors instead of printing them to the console. */
    private object FailingErrorHandler : ErrorHandler {
        override fun warning(exception: SAXParseException) {}

        override fun error(exception: SAXParseException): Unit = throw exception

        override fun fatalError(exception: SAXParseException): Unit = throw exception
    }
}
