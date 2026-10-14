package dev.sluide.httpclient.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaTypesTest {
    @Test
    fun `the media type drops parameters and case`() {
        assertEquals("text/html", MediaTypes.mediaType("Text/HTML; charset=UTF-8"))
        assertEquals("", MediaTypes.mediaType(null))
    }

    @Test
    fun `bodies are highlighted as JSON, XML or HTML`() {
        assertEquals("json", MediaTypes.language("application/json; charset=utf-8"))
        assertEquals("json", MediaTypes.language("application/problem+json"))
        assertEquals("xml", MediaTypes.language("text/xml"))
        assertEquals("xml", MediaTypes.language("application/atom+xml"))
        assertEquals("html", MediaTypes.language("text/html"))
        assertNull(MediaTypes.language("text/plain"))
        assertNull(MediaTypes.language("multipart/form-data; boundary=x"))
        assertNull(MediaTypes.language(null))
    }
}
