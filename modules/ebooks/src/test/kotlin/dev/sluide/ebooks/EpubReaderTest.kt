package dev.sluide.ebooks

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpubReaderTest {
    @TempDir
    lateinit var dir: Path

    private val chapters =
        listOf(
            TestChapter("c1.xhtml", "<h1>One</h1><p>First.</p>", title = "One"),
            TestChapter("text/c2.xhtml", "<h2>Two</h2><p>Second.</p>", title = "Two"),
        )

    private fun book(
        epub: TestEpub,
        limits: EpubLimits = EpubLimits(),
    ): EpubBook {
        val result = EpubReader.read(epub.write(dir), limits)
        return assertIs<EpubResult.Book>(result).book
    }

    private fun invalid(
        epub: TestEpub,
        limits: EpubLimits = EpubLimits(),
    ): String = assertIs<EpubResult.Invalid>(EpubReader.read(epub.write(dir), limits)).reason

    @Test
    fun `reads metadata and the spine in order`() {
        val book = book(TestEpub(chapters, title = "My Book", language = "de"))
        assertEquals("My Book", book.title)
        assertEquals("de", book.language)
        assertFalse(book.fixedLayout)
        assertEquals(listOf("OEBPS/c1.xhtml", "OEBPS/text/c2.xhtml"), book.chapters.map { it.href })
        assertTrue("First." in book.chapters[0].xhtml)
    }

    @Test
    fun `title falls back to the file name and a pre-paginated rendition is fixed layout`() {
        val result =
            EpubReader.read(
                TestEpub(chapters, title = null, language = null, fixedLayout = true).write(dir, "My Novel.epub"),
            )
        val book = assertIs<EpubResult.Book>(result).book
        assertEquals("My Novel", book.title)
        assertNull(book.language)
        assertTrue(book.fixedLayout)
    }

    @Test
    fun `toc comes from the nav document with nesting, hrefs resolved and chapters titled by it`() {
        val toc = listOf(TestToc("Part", "c1.xhtml", listOf(TestToc("Section", "text/c2.xhtml#s1"))))
        val book = book(TestEpub(chapters, toc = toc))
        assertEquals(
            listOf(
                TocEntry("Part", "OEBPS/c1.xhtml", listOf(TocEntry("Section", "OEBPS/text/c2.xhtml#s1", emptyList()))),
            ),
            book.toc,
        )
        assertEquals(listOf("Part", "Section"), book.chapters.map { it.title })
    }

    @Test
    fun `toc comes from the NCX of an EPUB 2`() {
        val toc = listOf(TestToc("Part", "c1.xhtml", listOf(TestToc("Section", "text/c2.xhtml"))))
        val book = book(TestEpub(chapters, version = 2, toc = toc))
        assertEquals(
            "OEBPS/text/c2.xhtml",
            book.toc
                .single()
                .children
                .single()
                .href,
        )
        assertEquals(
            "Section",
            book.toc
                .single()
                .children
                .single()
                .title,
        )
    }

    @Test
    fun `without a table of contents the spine items are titled by heading, else title`() {
        val spine =
            listOf(
                TestChapter("a.xhtml", "<p>no heading</p>", title = "Page Title"),
                TestChapter("b.xhtml", "<h3>Heading</h3>", title = "ignored"),
            )
        val book = book(TestEpub(spine, toc = emptyList()))
        assertEquals(listOf("Page Title", "Heading"), book.toc.map { it.title })
        assertEquals(listOf("OEBPS/a.xhtml", "OEBPS/b.xhtml"), book.toc.map { it.href })
    }

    @Test
    fun `resources are kept by zip path with their media type`() {
        val book =
            book(
                TestEpub(
                    chapters,
                    resources =
                        mapOf(
                            "images/a.png" to TestEpub.PNG,
                            "style.css" to "p{}".toByteArray(),
                        ),
                ),
            )
        assertEquals("image/png", book.resources.getValue("OEBPS/images/a.png").mediaType)
        assertEquals("text/css", book.resources.getValue("OEBPS/style.css").mediaType)
        assertTrue(book.resources.keys.none { it.endsWith(".xhtml") })
    }

    @Test
    fun `a manifest media type that is not a plain type makes the resource unusable, a plain one is lower-cased`() {
        val epub =
            TestEpub(
                chapters,
                resources =
                    mapOf(
                        "images/a.png" to TestEpub.PNG,
                        "images/b.png" to TestEpub.PNG,
                        "images/c.png" to TestEpub.PNG,
                    ),
                mediaTypes =
                    mapOf(
                        "images/a.png" to "image/png&quot; onerror=&quot;alert(1)",
                        "images/b.png" to "IMAGE/PNG",
                        "images/c.png" to "image/png; charset=x",
                    ),
            )
        val resources = book(epub).resources
        assertEquals(setOf("OEBPS/images/b.png"), resources.keys)
        assertEquals("image/png", resources.getValue("OEBPS/images/b.png").mediaType)
    }

    @Test
    fun `only images, fonts and style sheets are kept in memory`() {
        val names = listOf("a.png", "f.otf", "x.woff2", "s.css", "a.mp3", "v.mp4", "r.smil", "j.js", "d.pdf")
        val types =
            listOf(
                "image/png",
                "application/vnd.ms-opentype",
                "application/font-woff2",
                "text/css",
                "audio/mpeg",
                "video/mp4",
                "application/smil+xml",
                "text/javascript",
                "application/pdf",
            )
        val epub =
            TestEpub(
                chapters,
                resources = names.associateWith { byteArrayOf(1) },
                mediaTypes = names.zip(types).toMap(),
            )
        assertEquals(setOf("a.png", "f.otf", "x.woff2", "s.css").map { "OEBPS/$it" }.toSet(), book(epub).resources.keys)
    }

    @Test
    fun `an XML declaration encoding is honoured`() {
        val latin1 =
            "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><html><body><p>Café</p></body></html>".toByteArray(
                Charsets.ISO_8859_1,
            )
        val book = book(TestEpub(chapters, overrides = mapOf("OEBPS/c1.xhtml" to latin1)))
        assertTrue("Café" in book.chapters[0].xhtml)
    }

    @Test
    fun `rights xml is DRM`() {
        assertEquals(EpubResult.Drm, EpubReader.read(TestEpub(chapters, rights = true).write(dir)))
    }

    @Test
    fun `an encryption other than font obfuscation is DRM`() {
        val encryption = encryption("http://www.w3.org/2001/04/xmlenc#aes128-cbc", "OEBPS/c1.xhtml")
        assertEquals(EpubResult.Drm, EpubReader.read(TestEpub(chapters, encryptionXml = encryption).write(dir)))
    }

    @Test
    fun `font obfuscation alone is not DRM and the obfuscated font is dropped`() {
        val encryption = encryption("http://www.idpf.org/2008/embedding", "OEBPS/fonts/f.otf")
        val epub =
            TestEpub(
                chapters,
                encryptionXml = encryption,
                resources =
                    mapOf(
                        "fonts/f.otf" to byteArrayOf(1, 2),
                        "images/a.png" to TestEpub.PNG,
                    ),
            )
        val book = book(epub)
        assertEquals(setOf("OEBPS/images/a.png"), book.resources.keys)
    }

    private fun encryption(
        algorithm: String,
        uri: String,
    ) = """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
        xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
        <enc:EncryptedData><enc:EncryptionMethod Algorithm="$algorithm"/>
        <enc:CipherData><enc:CipherReference URI="$uri"/></enc:CipherData></enc:EncryptedData></encryption>"""

    @Test
    fun `a file that is not a zip is invalid`() {
        val file = dir.resolve("x.epub").also { it.writeText("hello") }
        assertEquals(EpubResult.Invalid("not a zip file"), EpubReader.read(file))
    }

    @Test
    fun `a missing file is invalid, not an exception`() {
        assertIs<EpubResult.Invalid>(EpubReader.read(dir.resolve("missing.epub")))
    }

    @Test
    fun `a missing container is invalid`() {
        assertEquals(
            "no META-INF/container.xml",
            invalid(
                TestEpub(
                    chapters,
                    overrides =
                        mapOf("META-INF/container.xml" to null),
                ),
            ),
        )
    }

    @Test
    fun `a missing package file is invalid`() {
        assertEquals(
            "package file missing",
            invalid(TestEpub(chapters, overrides = mapOf("OEBPS/content.opf" to null))),
        )
    }

    @Test
    fun `a book without chapters is invalid`() {
        assertEquals("no chapters", invalid(TestEpub(emptyList(), toc = emptyList())))
    }

    @Test
    fun `broken XML names the file`() {
        assertEquals(
            "unreadable XML in OEBPS/content.opf",
            invalid(
                TestEpub(
                    chapters,
                    overrides =
                        mapOf("OEBPS/content.opf" to "<package><oops>".toByteArray()),
                ),
            ),
        )
        assertEquals(
            "unreadable XML in META-INF/container.xml",
            invalid(
                TestEpub(
                    chapters,
                    overrides =
                        mapOf("META-INF/container.xml" to "nope".toByteArray()),
                ),
            ),
        )
    }

    @Test
    fun `too many entries, too many bytes in total and too many in one entry are too large`() {
        val epub = TestEpub(chapters, resources = mapOf("big.bin" to ByteArray(5000)))
        assertEquals("too large", invalid(epub, EpubLimits(maxEntries = 3)))
        assertEquals("too large", invalid(epub, EpubLimits(maxTotalBytes = 4000)))
        assertEquals("too large", invalid(epub, EpubLimits(maxEntryBytes = 4000)))
        assertIs<EpubResult.Book>(EpubReader.read(epub.write(dir), EpubLimits(maxEntryBytes = 6000)))
    }

    @Test
    fun `the limits count inflated bytes, not what the header says`() {
        val epub = TestEpub(chapters, resources = mapOf("big.bin" to ByteArray(50_000)))
        val bytes = epub.write(dir).readBytes()
        // Central directory entries (PK 01 02) of big.bin: claim a size of 10 bytes (the field at offset 24).
        var found = 0
        for (i in 0 until bytes.size - 46) {
            if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4B.toByte() && bytes[i + 2] == 1.toByte() &&
                bytes[i + 3] == 2.toByte()
            ) {
                val nameLength = (bytes[i + 28].toInt() and 0xFF) or ((bytes[i + 29].toInt() and 0xFF) shl 8)
                if (String(bytes, i + 46, nameLength) == "OEBPS/big.bin") {
                    for (k in 0..3) bytes[i + 24 + k] = if (k == 0) 10 else 0
                    found++
                }
            }
        }
        assertEquals(1, found)
        val lying = dir.resolve("lying.epub").also { it.writeBytes(bytes) }
        assertEquals(EpubResult.Invalid("too large"), EpubReader.read(lying, EpubLimits(maxEntryBytes = 4000)))
        assertEquals(EpubResult.Invalid("too large"), EpubReader.read(lying, EpubLimits(maxTotalBytes = 4000)))
    }

    @Test
    fun `a DOCTYPE with an external DTD is parsed without fetching it`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/evil.dtd"
            val doctype = "<!DOCTYPE package SYSTEM \"$url\">"
            val opf = TestEpub(chapters).entries().getValue("OEBPS/content.opf").decodeToString()
            val withDoctype = opf.replace("?>", "?>$doctype")
            val epub = TestEpub(chapters, overrides = mapOf("OEBPS/content.opf" to withDoctype.toByteArray()))
            assertIs<EpubResult.Book>(EpubReader.read(epub.write(dir)))
        } finally {
            server.stop(0)
        }
        // A fetch would have reached the server before read returned; the server answers synchronously.
        assertEquals(0, requests.get())
    }
}
