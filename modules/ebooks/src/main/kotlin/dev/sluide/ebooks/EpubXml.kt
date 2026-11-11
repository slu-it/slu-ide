package dev.sluide.ebooks

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.nio.charset.Charset
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** The XML of the package files (container, OPF, encryption), parsed without loading anything from outside. */
internal object EpubXml {
    private fun factory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newDefaultInstance().apply {
            // The prefixes stay as written (`dc:title`); the helpers below compare local names.
            isNamespaceAware = false
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            // A DOCTYPE (EPUB 2 files have an XHTML one) parses, but its DTD and entities are never fetched.
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }

    /** Throws on anything that is not well-formed XML. */
    fun parse(bytes: ByteArray): Document {
        val builder = factory().newDocumentBuilder()
        // Second line of defence: whatever asks for an external resource gets an empty one.
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        builder.setErrorHandler(null)
        return builder.parse(ByteArrayInputStream(bytes))
    }

    private fun startsWith(
        bytes: ByteArray,
        vararg prefix: Int,
    ) = bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }

    /** Text of an XML or XHTML file as a string: the BOM or the declared encoding, else UTF-8. */
    fun decode(bytes: ByteArray): String {
        if (startsWith(bytes, 0xFE, 0xFF)) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        if (startsWith(bytes, 0xFF, 0xFE)) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val declared =
            Regex(
                "^\\s*<\\?xml[^>]*encoding\\s*=\\s*[\"']([A-Za-z0-9._-]+)[\"']",
            ).find(head)?.groupValues?.get(1)
        val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(bytes, charset)
    }
}

internal fun Node.local(): String = nodeName.substringAfter(':')

internal fun Element.childElements(): List<Element> {
    val result = mutableListOf<Element>()
    var node = firstChild
    while (node != null) {
        if (node is Element) result += node
        node = node.nextSibling
    }
    return result
}

internal fun Element.children(local: String): List<Element> = childElements().filter { it.local() == local }

internal fun Element.descendants(local: String): List<Element> {
    val result = mutableListOf<Element>()

    fun walk(element: Element) {
        for (child in element.childElements()) {
            if (child.local() == local) result += child
            walk(child)
        }
    }
    walk(this)
    return result
}
