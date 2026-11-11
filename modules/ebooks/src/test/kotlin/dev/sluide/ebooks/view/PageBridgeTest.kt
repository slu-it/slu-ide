package dev.sluide.ebooks.view

import org.junit.jupiter.params.ParameterizedTest
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
