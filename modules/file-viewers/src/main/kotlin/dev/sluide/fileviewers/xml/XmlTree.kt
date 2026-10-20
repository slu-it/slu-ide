package dev.sluide.fileviewers.xml

import java.io.StringReader
import javax.xml.namespace.QName
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/** A node of the XML a preview shows; the `<?xml?>` declaration and whitespace-only text are not nodes. */
internal sealed interface XmlNode

internal class XmlElement(
    val name: String,
    val attributes: List<Pair<String, String>>,
    /** 0-based source line the start tag starts on. */
    val line: Int,
) : XmlNode {
    val children = ArrayList<XmlNode>()

    /** Whether the end tag was read; not so for elements open at an error or at the cut-off. */
    var closed = false
}

/** Trimmed text, entity references already decoded. */
internal class XmlText(
    val text: String,
) : XmlNode

internal class XmlCdata(
    val text: String,
) : XmlNode

internal class XmlComment(
    val text: String,
) : XmlNode

/** A processing instruction, as `target data`. */
internal class XmlInstruction(
    val text: String,
) : XmlNode

/** A DOCTYPE as written, `<!DOCTYPE …>`; never loaded or expanded. */
internal class XmlDoctype(
    val text: String,
) : XmlNode

/** The nodes before the first error (reported as such) or the first `maxNodes`, with open elements closed. */
internal class ParsedXml(
    val nodes: List<XmlNode>,
    val error: String?,
    val truncated: Boolean,
)

/** Parses XML text into [XmlNode]s with the JDK's StAX parser, hardened against external entities (ADR 0065). */
internal object XmlParser {
    /** `ParseError at [row,col]:[1,10]\nMessage: ` in front of the messages of the JDK parser. */
    private val prefix = Regex("""^ParseError at \[row,col]:\[(\d+),(\d+)]\s*Message:\s*""")

    private val completeReference = Regex("&#?\\w+;")

    /** Deeper nesting is an error; it keeps the page (and its recursion) bounded. */
    private const val MAX_DEPTH = 1000

    private fun factory(): XMLInputFactory =
        // Not newFactory(): a StAX implementation on the class path must never replace the hardened JDK one.
        XMLInputFactory.newDefaultFactory().apply {
            // A DOCTYPE is reported as text, never loaded; so its entities are undeclared, which is an error.
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty("javax.xml.accessExternalDTD", "")
            // Prefixes and xmlns attributes show as written; CDATA stays CDATA; entity references split the text.
            setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false)
            setProperty(XMLInputFactory.IS_COALESCING, false)
            setProperty("http://java.sun.com/xml/stream/properties/report-cdata-event", true)
            setProperty("jdk.xml.maxElementDepth", MAX_DEPTH)
            // Since JDK 24 the built-in entities (&amp; &lt; …) count against these limits (100,000 each), so valid
            // files with more references fail. Safe to lift: with DTDs off only built-in and character references
            // exist.
            setProperty("jdk.xml.maxGeneralEntitySizeLimit", 0)
            setProperty("jdk.xml.totalEntitySizeLimit", 0)
        }

    /** The name as written: with the prefix, which a parser that ignores namespaces keeps apart from the local part. */
    private fun QName.written() = if (prefix.isNullOrEmpty()) localPart else "$prefix:$localPart"

    /**
     * The DOCTYPE in [source] from [from] (the end of the event before it) to [end]: the parser's own text for it is
     * cut short at some declarations (a SYSTEM entity), so the source is taken where it is certain (offsets of a string
     * source are exact).
     */
    private fun doctype(
        source: String,
        from: Int,
        end: Int,
        reported: String,
    ): String {
        val start = if (from in 0..source.length) source.indexOf("<!DOCTYPE", from) else -1
        return if (start in 0 until end && end <= source.length) source.substring(start, end) else reported
    }

    /** The line breaks (`\n`, `\r\n` and lone `\r` each count once) in [source] from [start] to [end]. */
    private fun lineBreaks(
        source: String,
        start: Int,
        end: Int,
    ): Int {
        var breaks = 0
        for (i in start until minOf(end, source.length)) {
            val c = source[i]
            if (c == '\n' || (c == '\r' && source.getOrNull(i + 1) != '\n')) breaks++
        }
        return breaks
    }

    /** The 0-based line the start tag that ends at [end] starts on; [endLine] is the 1-based line it ends on. */
    private fun startLine(
        source: String,
        end: Int,
        endLine: Int,
    ): Int {
        val start = if (end in 1..source.length) source.lastIndexOf('<', end - 1) else -1
        return if (start >= 0) endLine - 1 - lineBreaks(source, start, end) else endLine - 1
    }

    fun parse(
        text: String,
        maxNodes: Int,
    ): ParsedXml {
        val top = ArrayList<XmlNode>()
        val stack = ArrayList<XmlElement>()
        val pending = StringBuilder()
        var error: String? = null
        var truncated = false
        var count = 0
        // The offset where the last event other than text ends.
        var lastEnd = 0
        var reader: XMLStreamReader? = null

        fun add(node: XmlNode): Boolean {
            if (count >= maxNodes) {
                truncated = true
                return false
            }
            count++
            (stack.lastOrNull()?.children ?: top) += node
            return true
        }

        fun flush(): Boolean {
            val trimmed = pending.toString().trim()
            pending.setLength(0)
            return trimmed.isEmpty() || add(XmlText(trimmed))
        }

        try {
            val r = factory().createXMLStreamReader(StringReader(text))
            reader = r
            try {
                while (r.hasNext()) {
                    val previousEnd = lastEnd
                    val event = r.next()
                    if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.SPACE) {
                        pending.append(r.text)
                        continue
                    }
                    // Not for text: its offset is one ahead when a tag or reference follows.
                    lastEnd = r.location.characterOffset
                    if (!flush()) break
                    val more =
                        when (event) {
                            XMLStreamConstants.START_ELEMENT -> {
                                val attributes =
                                    (0 until r.attributeCount).map {
                                        r.getAttributeName(it).written() to r.getAttributeValue(it)
                                    }
                                val line = startLine(text, lastEnd, r.location.lineNumber)
                                val element = XmlElement(r.name.written(), attributes, line)
                                add(element).also { if (it) stack += element }
                            }

                            XMLStreamConstants.END_ELEMENT -> {
                                stack.removeLastOrNull()?.let { it.closed = true } != null
                            }

                            XMLStreamConstants.CDATA -> {
                                add(XmlCdata(r.text))
                            }

                            XMLStreamConstants.COMMENT -> {
                                add(XmlComment(r.text))
                            }

                            XMLStreamConstants.DTD -> {
                                add(XmlDoctype(doctype(text, previousEnd, lastEnd, r.text)))
                            }

                            XMLStreamConstants.PROCESSING_INSTRUCTION -> {
                                val data = r.piData.orEmpty()
                                add(XmlInstruction(r.piTarget + if (data.isEmpty()) "" else " $data"))
                            }

                            else -> {
                                true
                            }
                        }
                    if (!more) break
                }
            } finally {
                r.close()
            }
        } catch (e: XMLStreamException) {
            flush()
            error = describe(e, stack.lastOrNull(), text, lastEnd)
        } catch (e: RuntimeException) {
            // The JDK parser throws unchecked exceptions on some malformed input (a control character in a DTD).
            flush()
            error =
                where(reader?.location?.lineNumber, reader?.location?.columnNumber) + "the parser failed on this text"
        }
        return ParsedXml(top, error, truncated)
    }

    private fun where(
        line: Int?,
        column: Int?,
    ) = if (line != null && column != null && line > 0 && column > 0) "Line $line, column $column: " else ""

    /**
     * "Line 3, column 7: message" for [e], without the JDK's `ParseError at …` prefix; [open] is the innermost element
     * and [lastEnd] the offset in [source] where the last event read ended.
     */
    private fun describe(
        e: XMLStreamException,
        open: XmlElement?,
        source: String,
        lastEnd: Int,
    ): String {
        val raw = e.message.orEmpty()
        val match = prefix.find(raw)
        val message = raw.removeRange(match?.range ?: IntRange.EMPTY).trim()
        val line = e.location?.lineNumber?.takeIf { it > 0 } ?: match?.groupValues?.get(1)?.toInt()
        val column = e.location?.columnNumber?.takeIf { it > 0 } ?: match?.groupValues?.get(2)?.toInt()
        val where = where(line, column)
        if (open != null && message.startsWith("XML document structures must start and end within the same entity")) {
            // Only text (and complete references) follows the last event: the element is not closed; else some
            // markup is (a tag, comment, CDATA section, processing instruction or reference).
            val rest = source.substring(minOf(lastEnd, source.length))
            if (rest.replace(completeReference, "").none { it == '<' || it == '&' }) {
                return where + "the element <${open.name}> opened on line ${open.line + 1} is not closed"
            }
            return where +
                "the file ends inside markup (a tag, comment, CDATA section, processing instruction or reference)"
        }
        return where + message
    }
}
