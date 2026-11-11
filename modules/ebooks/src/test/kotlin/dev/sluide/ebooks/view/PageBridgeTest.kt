package dev.sluide.ebooks.view

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageBridgeTest {
    @ParameterizedTest
    @ValueSource(strings = ["", "about:blank", "about:blank#ch3"])
    fun `the loaded page and its anchors are the own page`(location: String) {
        assertTrue(isOwnPage(location))
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["https://example.com/", "http://example.com/#x", "mailto:a@b.c", "file:///x", "about:blank2"],
    )
    fun `everything else is a navigation away`(location: String) {
        assertFalse(isOwnPage(location))
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "https://example.com/a b.html|https://example.com/a%20b.html",
            "https://example.com/Käse?q=ü ö#ab c|https://example.com/K%C3%A4se?q=%C3%BC%20%C3%B6#ab%20c",
            "http://example.com/a%20b|http://example.com/a%20b",
            "mailto:Jörg Müller <j@example.com>?subject=Hallo Welt|" +
                "mailto:J%C3%B6rg%20M%C3%BCller%20%3Cj@example.com%3E?subject=Hallo%20Welt",
            "https://example.com/\"x\"|https://example.com/%22x%22",
            "https://example.com/a\uD83D\uDE00b|https://example.com/a%F0%9F%98%80b",
        ],
    )
    fun `a link with spaces or non-ASCII characters is percent-encoded instead of dropped`(
        href: String,
        expected: String,
    ) {
        assertEquals(expected, externalLink(href)?.toString())
    }

    @Test
    fun `only http, https and mailto links go to the browser`() {
        assertEquals("https://example.com/a", externalLink("https://example.com/a")?.toString())
        assertEquals("http://example.com", externalLink(" http://example.com ")?.toString())
        assertEquals("mailto", externalLink("MAILTO:a@b.c")?.scheme?.lowercase())
        assertNull(externalLink("file:///etc/passwd"))
        assertNull(externalLink("javascript:alert(1)"))
        assertNull(externalLink("#frag"))
        assertNull(externalLink("not a uri"))
    }
}
