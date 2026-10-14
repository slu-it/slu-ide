package dev.sluide.httpclient.ui

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResponseFormatterTest {
    private companion object {
        const val TOO_COMPLEX = "Not pretty-printed: too deeply nested or too large to format"
    }

    private fun format(
        body: String,
        contentType: String?,
    ) = ResponseFormatter.format(body.toByteArray(), contentType)

    @Test
    fun `JSON is pretty-printed keeping key order and number spelling`() {
        val body = """{"z":1,"a":[1.50,2e3,{}],"m":{"k":"vé"},"e":[]}"""

        val formatted = format(body, "application/json; charset=utf-8")

        assertEquals(
            """
            |{
            |  "z": 1,
            |  "a": [
            |    1.50,
            |    2e3,
            |    {}
            |  ],
            |  "m": {
            |    "k": "vé"
            |  },
            |  "e": []
            |}
            """.trimMargin(),
            formatted.text,
        )
        assertEquals(emptyList(), formatted.notes)
        assertEquals("json", formatted.language)
        assertEquals(emptyList(), formatted.notes)
        assertFalse(formatted.binary)
    }

    @Test
    fun `invalid JSON is shown as received`() {
        for (body in listOf("""{"a": 1""", """{"a": 1} {"b": 2}""", "not json")) {
            val formatted = format(body, "application/problem+json")

            assertEquals(body, formatted.text)
            assertEquals("json", formatted.language)
            assertEquals(listOf("Not valid JSON: shown as received"), formatted.notes)
        }
    }

    @Test
    fun `XML is indented, keeping its declaration`() {
        val body = """<?xml version="1.0"?>""" + "\n<a>\n  <b x=\"1\">text</b><c/><!-- note --></a>"

        val formatted = format(body, "application/xml")

        assertEquals(
            """
            |<?xml version="1.0"?>
            |<a>
            |  <b x="1">text</b>
            |  <c/>
            |  <!-- note -->
            |</a>
            |
            """.trimMargin(),
            formatted.text,
        )
        assertEquals("xml", formatted.language)
    }

    @Test
    fun `XML with a DOCTYPE is shown as received, never resolving entities`() {
        val body =
            """<?xml version="1.0"?><!DOCTYPE a [<!ENTITY e SYSTEM "file:///etc/passwd">]><a>&e;</a>"""

        val formatted = format(body, "text/xml")

        assertEquals(body, formatted.text)
        assertEquals(listOf("Not valid XML: shown as received"), formatted.notes)
    }

    @Test
    fun `malformed XML is shown as received`() {
        assertEquals("<a><b></a>", format("<a><b></a>", "application/xml").text)
    }

    @Test
    fun `HTML and other text are shown as received`() {
        val html = format("<html><body><p>Hi</body>", "text/html")
        val plain = format("{\"a\":1}", "text/plain")

        assertEquals("<html><body><p>Hi</body>", html.text)
        assertEquals("html", html.language)
        assertEquals("{\"a\":1}", plain.text)
        assertNull(plain.language)
        assertEquals(emptyList(), plain.notes)
    }

    @Test
    fun `the charset comes from the content type`() {
        val body = "café".toByteArray(Charsets.ISO_8859_1)

        assertEquals("café", ResponseFormatter.format(body, "text/plain; charset=\"ISO-8859-1\"").text)
        assertNull(ResponseFormatter.declaredCharset("text/plain; charset=nonsense"))
        assertNull(ResponseFormatter.declaredCharset("text/plain"))
        assertEquals(Charsets.UTF_16BE, ResponseFormatter.declaredCharset("text/plain;Charset=UTF-16BE"))
    }

    @Test
    fun `bodies that do not decode or contain NUL are binary`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) + ByteArray(12_587)

        val formatted = ResponseFormatter.format(png, "image/png")

        assertTrue(formatted.binary)
        assertEquals("Binary body, 12.3 KB, image/png", formatted.text)
        assertNull(formatted.language)
        assertEquals("Binary body, 3 B", ResponseFormatter.format(byteArrayOf(1, 0, 2), null).text)
        assertTrue(ResponseFormatter.format(byteArrayOf(0xC3.toByte()), "application/json; charset=utf-8").binary)
    }

    @Test
    fun `large bodies are not pretty-printed`() {
        val body = "[" + "1,".repeat(ResponseFormatter.PRETTY_PRINT_LIMIT / 2) + "1]"

        val formatted = format(body, "application/json")

        assertEquals(body, formatted.text)
        assertEquals(listOf("Not pretty-printed: larger than 1.0 MB"), formatted.notes)
    }

    @Test
    fun `empty bodies stay empty`() {
        val formatted = format("", "application/json")

        assertEquals("", formatted.text)
        assertEquals(emptyList(), formatted.notes)
    }

    @Test
    fun `byte order marks are dropped and choose the charset`() {
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val utf16le = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

        val json = ResponseFormatter.format(utf8 + """{"a":"é"}""".toByteArray(), "application/json")
        val xml = ResponseFormatter.format(utf8 + """<?xml version="1.0"?><a/>""".toByteArray(), "text/xml")
        val utf16 = utf16le + """{"a":1}""".toByteArray(Charsets.UTF_16LE)

        assertEquals("{\n  \"a\": \"é\"\n}", json.text)
        assertEquals("{\"a\":\"é\"}", json.raw)
        assertEquals("<?xml version=\"1.0\"?>\n<a/>\n", xml.text)
        for (contentType in listOf("application/json", "application/json; charset=utf-16le")) {
            val formatted = ResponseFormatter.format(utf16, contentType)
            assertEquals("{\n  \"a\": 1\n}", formatted.text, contentType)
            assertEquals(emptyList(), formatted.notes, contentType)
        }
    }

    @Test
    fun `text without a charset that is not UTF-8 is decoded as windows-1252`() {
        val body = "café €5".toByteArray(ResponseFormatter.FALLBACK_CHARSET)

        val plain = ResponseFormatter.format(body, "text/plain")
        val json =
            ResponseFormatter.format(
                "\"café\"".toByteArray(ResponseFormatter.FALLBACK_CHARSET),
                "application/json",
            )

        assertEquals("café €5", plain.text)
        assertEquals(listOf("Not UTF-8 and no charset declared: decoded as windows-1252"), plain.notes)
        assertEquals("\"café\"", json.text)
        assertTrue(ResponseFormatter.format(body, "text/plain; charset=utf-8").binary)
        assertTrue(ResponseFormatter.format(body, "application/octet-stream").binary)
        assertTrue(ResponseFormatter.format(byteArrayOf(0x41, 0, 0xE9.toByte()), "text/plain").binary)
    }

    @Test
    fun `processing instructions are no XML declaration`() {
        val body = """<?xml-stylesheet href="a.xsl"?><a/>"""

        // Kept once; the transformer writes nodes before the root element without a line break.
        assertEquals("<?xml-stylesheet href=\"a.xsl\"?><a/>\n", format(body, "application/xml").text)
    }

    @Test
    fun `JSON beyond the parser's limits says so`() {
        val body = "[".repeat(5000) + "]".repeat(5000)

        val formatted = format(body, "application/json")

        assertEquals(body, formatted.text)
        assertEquals(listOf(TOO_COMPLEX), formatted.notes)
    }

    @Test
    fun `XML beyond the JDK's limits says so`() {
        val body = "<a>".repeat(100_000) + "</a>".repeat(100_000)

        val formatted = format(body, "application/xml")

        assertEquals(body, formatted.text)
        assertEquals(listOf(TOO_COMPLEX), formatted.notes)
    }

    @Test
    fun `sizes are human-readable`() {
        assertEquals("0 B", ResponseFormatter.size(0))
        assertEquals("1023 B", ResponseFormatter.size(1023))
        assertEquals("1.0 KB", ResponseFormatter.size(1024))
        assertEquals("12.3 KB", ResponseFormatter.size(12_595))
        assertEquals("1.0 MB", ResponseFormatter.size(1024L * 1024 - 1))
        assertEquals("2.5 GB", ResponseFormatter.size(5L * 1024 * 1024 * 1024 / 2))
    }

    @Test
    fun `durations are human-readable`() {
        assertEquals("0 ms", ResponseFormatter.duration(Duration.ZERO))
        assertEquals("850 ms", ResponseFormatter.duration(Duration.ofMillis(850)))
        assertEquals("1.25 s", ResponseFormatter.duration(Duration.ofMillis(1250)))
        assertEquals("59.99 s", ResponseFormatter.duration(Duration.ofMillis(59_994)))
        assertEquals("1 min 0 s", ResponseFormatter.duration(Duration.ofMillis(59_996)))
        assertEquals("2 min 5 s", ResponseFormatter.duration(Duration.ofSeconds(125)))
    }
}
