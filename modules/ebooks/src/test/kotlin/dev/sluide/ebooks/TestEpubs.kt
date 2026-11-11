package dev.sluide.ebooks

import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream

/** A chapter of a [TestEpub]: [file] is relative to `OEBPS`, [body] goes inside `<body>`, [head] inside `<head>`. */
internal data class TestChapter(
    val file: String,
    val body: String,
    val head: String = "",
    val title: String = file,
)

/** A table of contents entry of a [TestEpub]; [href] is relative to `OEBPS`. */
internal data class TestToc(
    val title: String,
    val href: String,
    val children: List<TestToc> = emptyList(),
)

/**
 * Describes an EPUB for tests; [write] turns it into a zip file. Everything lives in `OEBPS/`.
 *
 * - [version] 3 writes a nav document (`nav.xhtml`), 2 an NCX (`toc.ncx`) and XHTML 1.1 DOCTYPEs.
 * - [toc] null gives one entry per chapter; an empty list writes no table of contents at all (the reader then falls
 *   back to the spine).
 * - [resources] maps a path relative to `OEBPS` (`images/a.png`, `style.css`) to its bytes; every one is in the
 *   manifest, the media type comes from the extension. Chapters link a stylesheet with `<link>` in their [head].
 * - [encryptionXml] and [rights] write `META-INF/encryption.xml` and `rights.xml`; [fixedLayout] adds the
 *   `rendition:layout` meta (version 3).
 * - [mediaTypes] replaces the manifest `media-type` of a resource, written as is (XML, so `&quot;` for a quote).
 * - [overrides] replaces (or with null removes) any zip entry by its full name, e.g. `META-INF/container.xml`,
 *   `OEBPS/content.opf`.
 */
internal data class TestEpub(
    val chapters: List<TestChapter>,
    val version: Int = 3,
    val title: String? = "Test Book",
    val language: String? = "en",
    val toc: List<TestToc>? = null,
    val resources: Map<String, ByteArray> = emptyMap(),
    val encryptionXml: String? = null,
    val rights: Boolean = false,
    val fixedLayout: Boolean = false,
    val doctypeExtra: String = "",
    val mediaTypes: Map<String, String> = emptyMap(),
    val overrides: Map<String, ByteArray?> = emptyMap(),
) {
    fun write(
        dir: Path,
        name: String = "book.epub",
    ): Path {
        val file = dir.resolve(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((entry, bytes) in entries()) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    fun entries(): Map<String, ByteArray> {
        val all = linkedMapOf<String, ByteArray>()
        all["mimetype"] = "application/epub+zip".toByteArray()
        all["META-INF/container.xml"] = CONTAINER.toByteArray()
        all["OEBPS/content.opf"] = opf().toByteArray()
        chapters.forEach { all["OEBPS/${it.file}"] = chapterXhtml(it).toByteArray() }
        val entries = toc ?: chapters.map { TestToc(it.title, it.file) }
        if (entries.isNotEmpty()) {
            if (version >= 3) {
                all["OEBPS/nav.xhtml"] = nav(entries).toByteArray()
            } else {
                all["OEBPS/toc.ncx"] = ncx(entries).toByteArray()
            }
        }
        resources.forEach { (path, bytes) -> all["OEBPS/$path"] = bytes }
        encryptionXml?.let { all["META-INF/encryption.xml"] = it.toByteArray() }
        if (rights) all["META-INF/rights.xml"] = "<rights/>".toByteArray()
        for ((entry, bytes) in overrides) if (bytes == null) all.remove(entry) else all[entry] = bytes
        return all
    }

    private fun mediaType(path: String) = EpubPaths.mediaTypeOf(path)

    private fun opf(): String {
        val items = StringBuilder()
        chapters.forEachIndexed { i, c ->
            items.append("<item id=\"c$i\" href=\"${c.file}\" media-type=\"application/xhtml+xml\"/>")
        }
        val hasToc = toc?.isEmpty() != true
        if (hasToc) {
            if (version >= 3) {
                items.append(
                    "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>",
                )
            } else {
                items.append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>")
            }
        }
        resources.keys.forEachIndexed { i, path ->
            items.append("<item id=\"r$i\" href=\"$path\" media-type=\"${mediaTypes[path] ?: mediaType(path)}\"/>")
        }
        val spine = chapters.indices.joinToString("") { "<itemref idref=\"c$it\"/>" }
        val meta =
            buildString {
                title?.let { append("<dc:title>$it</dc:title>") }
                language?.let { append("<dc:language>$it</dc:language>") }
                if (fixedLayout) append("<meta property=\"rendition:layout\">pre-paginated</meta>")
            }
        val ncxAttr = if (version < 3 && hasToc) " toc=\"ncx\"" else ""
        return """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="$version.0" unique-identifier="id">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">$meta</metadata>
            <manifest>$items</manifest><spine$ncxAttr>$spine</spine></package>"""
    }

    private fun chapterXhtml(chapter: TestChapter): String {
        val doctype =
            if (version >= 3) {
                "<!DOCTYPE html>"
            } else {
                "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" " +
                    "\"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\"$doctypeExtra>"
            }
        return """<?xml version="1.0" encoding="UTF-8"?>
            $doctype
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>${chapter.title}</title>${chapter.head}</head>
            <body>${chapter.body}</body></html>"""
    }

    private fun nav(entries: List<TestToc>): String {
        fun list(items: List<TestToc>): String =
            "<ol>" +
                items.joinToString("") {
                    val children = if (it.children.isEmpty()) "" else list(it.children)
                    "<li><a href=\"${it.href}\">${it.title}</a>$children</li>"
                } + "</ol>"
        return """<?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>Nav</title></head>
            <body><nav epub:type="landmarks"><ol><li><a href="${chapters.first().file}">Start</a></li></ol></nav>
            <nav epub:type="toc">${list(entries)}</nav></body></html>"""
    }

    private fun ncx(entries: List<TestToc>): String {
        var n = 0

        fun points(items: List<TestToc>): String =
            items.joinToString("") {
                val id = n++
                "<navPoint id=\"p$id\" playOrder=\"$n\"><navLabel><text>${it.title}</text></navLabel>" +
                    "<content src=\"${it.href}\"/>${points(it.children)}</navPoint>"
            }
        return """<?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/><docTitle><text>x</text></docTitle>
            <navMap>${points(entries)}</navMap></ncx>"""
    }

    companion object {
        private const val CONTAINER = """<?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles>
            <rootfile full-path="OEBPS/content.opf"
            media-type="application/oebps-package+xml"/>
            </rootfiles></container>"""

        /** A valid 1x1 PNG. */
        val PNG: ByteArray =
            java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
            )
    }
}
