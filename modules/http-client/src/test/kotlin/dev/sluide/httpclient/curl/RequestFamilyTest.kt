package dev.sluide.httpclient.curl

import dev.sluide.converting.ConversionResult
import dev.sluide.converting.FormatFamily
import dev.sluide.converting.FormatMatch
import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.TextFormat
import dev.sluide.converting.WriteResult
import dev.sluide.converting.convert
import dev.sluide.fileviewers.Highlighters
import dev.sluide.httpclient.ui.HttpSyntaxHighlighter
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class RequestFamilyTest {
    private val family = RequestFamily()

    @ParameterizedTest(name = "{0}")
    @MethodSource("detection")
    fun `detects curl commands and http requests`(
        text: String,
        format: TextFormat?,
    ) {
        val expected =
            when (format) {
                null -> emptyList()
                CurlFormat -> listOf(FormatMatch(CurlFormat, 100))
                else -> listOf(FormatMatch(HttpRequestFormat, 90))
            }
        assertEquals(expected, family.detect(text))
    }

    @Test
    fun `only curl output has the shell option`() {
        assertEquals(listOf(RequestFamily.SHELL), family.writeOptions(CurlFormat))
        assertEquals(listOf("POSIX", "cmd", "PowerShell"), RequestFamily.SHELL.choices)
        assertEquals(emptyList(), family.writeOptions(HttpRequestFormat))
        assertEquals(emptyList(), family.readOptions(CurlFormat))
    }

    @Test
    fun `http requests are highlighted as in the editor of http files`() {
        assertIs<HttpSyntaxHighlighter>(HttpRequestFormat.highlighter)
    }

    @Test
    fun `curl commands are highlighted as bash and the family converts no files`() {
        assertEquals(Highlighters.forLanguage("bash"), CurlFormat.highlighter)
        assertNotNull(CurlFormat.highlighter)
        assertFalse(family.convertsFiles)
    }

    @Test
    fun `a format of another family is refused by every operation, naming it`() {
        val other =
            object : TextFormat {
                override val id = "json"
                override val name = "JSON"
                override val language = "json"
                override val extensions = listOf("json")
            }
        val message = "json is not a format of the Requests family"

        listOf<() -> Any>(
            { family.read("{}", other, OptionValues()) },
            { family.write(emptyList(), other, OptionValues()) },
            { family.readOptions(other) },
            { family.writeOptions(other) },
        ).forEach { call -> assertEquals(message, assertFailsWith<IllegalArgumentException> { call() }.message) }
    }

    @Test
    fun `an exception inside a reader or writer becomes a failure with its message, else its class`() {
        assertEquals("boom", family.guarded({ it }) { throw IllegalStateException("boom") })
        assertEquals("IllegalStateException", family.guarded({ it }) { throw IllegalStateException() })
        assertEquals("ok", family.guarded({ "failed: $it" }) { "ok" })
        // A local class has no simple name; the failure is then empty text, not the word "null".
        assertEquals("", family.guarded({ it }) { throw object : RuntimeException() {} })
    }

    @Test
    fun `a duration that curl cannot write fails the conversion instead of throwing`() {
        // The reader takes `@timeout 9223372036854775807 s`; Duration.toMillis overflows when writing it back.
        val request = CurlRequest("https://x", timeout = Duration.ofSeconds(Long.MAX_VALUE))

        family.formats.forEach { format ->
            assertIs<WriteResult.Failed>(family.write(listOf(request), format, OptionValues()), format.id)
        }
    }

    @ParameterizedTest
    // U+00A0 and U+3000 are blank for Kotlin but no space for the curl tokenizer, which would refuse them.
    @ValueSource(strings = ["", " \n", "\u00A0", "\u3000"])
    fun `blank text reads as no requests, which write as empty text`(text: String) {
        family.formats.forEach { format ->
            assertEquals(ReadResult.Read(emptyList(), emptyList()), family.read(text, format, OptionValues()))
            assertEquals(WriteResult.Written("", emptyList()), family.write(emptyList(), format, OptionValues()))
        }
    }

    @Test
    fun `formats of other families are refused`() {
        val other =
            object : TextFormat {
                override val id = "json"
                override val name = "JSON"
                override val language = "json"
                override val extensions = listOf("json")
            }
        assertFailsWith<IllegalArgumentException> { family.read("{}", other, OptionValues()) }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["POSIX", "cmd", "PowerShell"])
    fun `http requests survive the way through curl`(shell: String) {
        val http =
            """
            ### Get users
            # @timeout 30 s
            GET https://{{host}}/users?q=a b&limit=10 HTTP/2
            Accept: application/json
            Authorization: Bearer {{token}}

            ### Create
            POST https://{{host}}/users
            Content-Type: application/json
            Authorization: Digest ada s3cret

            {
              "name": "O'Brien \"Ada\" 50% \\o/",
              "id": "{{${'$'}uuid}}"
            }

            ###
            # @no-redirect
            PUT https://{{host}}/avatar
            Content-Type: application/x-www-form-urlencoded

            a=1&
            < ./avatar.png

            ###
            POST https://{{host}}/upload
            Content-Type: multipart/form-data; boundary=WebAppBoundary

            --WebAppBoundary
            Content-Disposition: form-data; name="title"

            A title; with semicolon
            --WebAppBoundary
            Content-Disposition: form-data; name="file"; filename="a.png"
            Content-Type: image/png

            < ./img/a.png
            --WebAppBoundary--

            """.trimIndent()
        val options = OptionValues().with("shell", shell)

        val curl = family.convert(http, HttpRequestFormat, CurlFormat, options) as ConversionResult.Converted
        val back = family.convert(curl.text, CurlFormat, HttpRequestFormat, options) as ConversionResult.Converted

        assertEquals(http, back.text)
    }

    @Test
    fun `curl commands survive the way through http`() {
        val curl =
            """
            curl https://x/a \
              -L \
              -H 'Accept: a'

            # Second
            curl -X DELETE https://x/b
            """.trimIndent() + "\n"

        val http = family.convert(curl, CurlFormat, HttpRequestFormat, OptionValues()) as ConversionResult.Converted
        val back =
            family.convert(
                http.text,
                HttpRequestFormat,
                CurlFormat,
                OptionValues(),
            ) as ConversionResult.Converted

        assertEquals("GET https://x/a\nAccept: a\n\n### Second\n# @no-redirect\nDELETE https://x/b\n", http.text)
        assertEquals(curl, back.text)
    }

    @Test
    fun `is a Spring bean of the FormatFamily type`() {
        AnnotationConfigApplicationContext(RequestFamily::class.java).use { context ->
            assertEquals(
                listOf("Requests"),
                context.getBeansOfType(FormatFamily::class.java).values.map { it.name },
            )
        }
    }

    companion object {
        @JvmStatic
        fun detection() =
            listOf(
                Arguments.of("curl https://x", CurlFormat),
                Arguments.of("\n  $ curl -s https://x", CurlFormat),
                Arguments.of("curl.exe https://x", CurlFormat),
                Arguments.of("GET https://x", HttpRequestFormat),
                Arguments.of("https://x/a", HttpRequestFormat),
                Arguments.of("### Users\n# @no-redirect\nPOST {{host}}/users\nA: b\n\nbody", HttpRequestFormat),
                Arguments.of("@host = x\n\nGET https://{{host}}", HttpRequestFormat),
                Arguments.of("GET https://x\nnot a header", null),
                Arguments.of("a: b", null),
                Arguments.of("{\"a\": 1}", null),
                Arguments.of("GETTER https://x", null),
                Arguments.of("", null),
            )
    }
}
