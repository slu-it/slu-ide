package dev.sluide.httpclient.complete

import dev.sluide.httpclient.parse.HttpFileParser
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals

/** The contexts themselves, where the engine's fixed-part filter would hide a wrong one by emptying its items. */
class ContextDetectorTest {
    private fun detect(marked: String): Context {
        val caret = marked.indexOf('|')
        val text = marked.removeRange(caret, caret + 1)
        return ContextDetector.detect(text, caret, HttpFileParser.parse(text))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "@a=1|",
            "@bad|",
            "< file.js|",
            "< {%\nG|\n%}",
            "GET /a HTTP/1.1 |",
            "/a b |",
            "GET /a\nHost: x\n\nG|",
        ],
    )
    fun `finds nothing where a method or a version would only be filtered away`(marked: String) {
        val context = detect(marked)

        assertEquals(Context.Nothing(marked.indexOf('|')), context)
    }

    @Test
    fun `finds the places of methods, versions, header names and values`() {
        assertEquals(Context.Method(0), detect("G|"))
        assertEquals(Context.Method(2), detect("  G|"))
        assertEquals(Context.Version(7), detect("GET /a |"))
        assertEquals(Context.HeaderName(7, colonFollows = false, line = 1), detect("GET /a\nCo|"))
        assertEquals(Context.HeaderName(7, colonFollows = true, line = 1), detect("GET /a\nCo|: x"))
        assertEquals(Context.HeaderValue(21, "Content-Type"), detect("GET /a\nContent-Type: |"))
        assertEquals(Context.Tag(3), detect("# @|"))
        assertEquals(Context.Variable(6, closed = false, line = 0), detect("GET {{|"))
        assertEquals(Context.Variable(6, closed = true, line = 0), detect("GET {{|}}"))
    }
}
