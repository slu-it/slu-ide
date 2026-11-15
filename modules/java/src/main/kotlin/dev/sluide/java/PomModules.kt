package dev.sluide.java

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory

/** A pom with elements nested deeper than this has no modules (the JDK parser itself refuses at 100). */
private const val MAX_POM_DEPTH = 64

/** A parser factory of its own per call: JAXP factories are not guaranteed to be thread-safe. */
private fun newSaxFactory(): SAXParserFactory =
    SAXParserFactory.newDefaultInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }

/**
 * The folders listed as `<modules><module>` (also inside profiles) of the pom in [folder], absolute and normalized.
 * The pom is untrusted input: only a regular file is read, at most [MAX_FILE_BYTES] of it, a DOCTYPE or external
 * entity is refused, and a pom that is bigger, nested deeper than [MAX_POM_DEPTH] elements or does not parse has no
 * modules. Blocks on I/O.
 */
internal fun pomModules(folder: Path): Set<Path> {
    val pom = folder.resolve("pom.xml")
    if (!Files.isRegularFile(pom)) return emptySet()
    val bytes = readLimitedBytes(pom) ?: return emptySet()
    if (bytes.size > MAX_FILE_BYTES) return emptySet()
    val entries = mutableListOf<String>()
    val handler =
        object : DefaultHandler() {
            private val stack = ArrayList<String>()
            private val text = StringBuilder()

            override fun startElement(
                uri: String?,
                localName: String?,
                qName: String,
                attributes: Attributes?,
            ) {
                if (stack.size >= MAX_POM_DEPTH) throw SAXException("pom nested too deep")
                stack.add(qName)
                text.setLength(0)
            }

            override fun characters(
                ch: CharArray,
                start: Int,
                length: Int,
            ) {
                if (text.length < 4096) text.append(ch, start, length)
            }

            override fun endElement(
                uri: String?,
                localName: String?,
                qName: String,
            ) {
                if (stack.size >= 2 && qName == "module" &&
                    stack[stack.size - 2] == "modules"
                ) {
                    entries += text.toString()
                }
                stack.removeAt(stack.size - 1)
            }

            // A DOCTYPE is refused already; this keeps any external resource from being asked for.
            override fun resolveEntity(
                publicId: String?,
                systemId: String?,
            ): InputSource = InputSource(java.io.StringReader(""))
        }
    try {
        newSaxFactory().newSAXParser().parse(ByteArrayInputStream(bytes), handler)
    } catch (_: SAXException) {
        return emptySet()
    } catch (_: IOException) {
        return emptySet()
    } catch (_: ParserConfigurationException) {
        return emptySet()
    }
    return entries
        .map { it.trim() }
        .mapNotNull { entry ->
            try {
                val target = folder.resolve(entry).normalize()
                // A module may name the pom file itself.
                if (entry.endsWith(".xml")) target.parent else target
            } catch (_: java.nio.file.InvalidPathException) {
                null
            }
        }.toSet()
}

/**
 * The nearest folder above [folder], up to [top] (included), whose pom lists [folder] as a module (the entry may reach
 * several folders down, e.g. `services/billing`), or `null`.
 */
internal fun listingFolder(
    folder: Path,
    top: Path,
): Path? =
    generateSequence(folder.parent) { it.parent }
        .takeWhile { it.startsWith(top) }
        .firstOrNull { folder in pomModules(it) }
