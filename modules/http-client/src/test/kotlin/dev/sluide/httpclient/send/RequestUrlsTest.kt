package dev.sluide.httpclient.send

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RequestUrlsTest {
    private fun uri(
        url: String,
        host: String? = null,
    ) = RequestUrls.toUri(url, host)

    @Test
    fun `adds http when the scheme is missing`() {
        assertEquals(URI("http://localhost:8080/a"), uri("localhost:8080/a"))
        assertEquals(URI("https://example.com"), uri("https://example.com"))
    }

    @Test
    fun `takes the host header for URLs starting with a slash`() {
        assertEquals(URI("http://example.com:81/a?b=1"), uri("/a?b=1", " example.com:81"))
        assertFailsWith<RequestBuildException> { uri("/a") }
    }

    @Test
    fun `encodes spaces, non-ASCII and unsafe characters in path and query`() {
        assertEquals(
            "http://example.com/a%20b/%C3%A4?q=x%20y&json=%7B%22a%22:1%7D&e=%F0%9F%98%80",
            uri("http://example.com/a b/ä?q=x y&json={\"a\":1}&e=😀").toString(),
        )
    }

    @Test
    fun `keeps existing escapes and encodes lone percent signs`() {
        assertEquals("http://x/a%20b?p=100%25&q=%2F", uri("http://x/a%20b?p=100%&q=%2F").toString())
    }

    @Test
    fun `drops the fragment`() {
        assertEquals(URI("http://x/a?b=c"), uri("http://x/a?b=c#part"))
    }

    @Test
    fun `rejects URLs that cannot be sent`() {
        assertFailsWith<RequestBuildException> { uri("") }
        assertFailsWith<RequestBuildException> { uri("ftp://example.com/file") }
        assertFailsWith<RequestBuildException> { uri("http://exa mple.com/") }
    }

    @Test
    fun `adds http to URLs whose query contains a URL`() {
        assertEquals(URI("http://localhost:8080/login?next=http://a"), uri("localhost:8080/login?next=http://a"))
        assertEquals(
            URI("http://example.com/authorize?redirect_uri=http://localhost/cb"),
            uri("example.com/authorize?redirect_uri=http://localhost/cb"),
        )
    }
}
