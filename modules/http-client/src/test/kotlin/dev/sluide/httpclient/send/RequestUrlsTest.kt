package dev.sluide.httpclient.send

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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

    @Test
    fun `says why a URL cannot be sent`() {
        assertEquals("The request has no URL", assertFailsWith<RequestBuildException> { uri("") }.message)
        assertEquals(
            "The request has no URL",
            assertFailsWith<RequestBuildException> { uri("  #only-a-fragment") }.message,
        )
        assertEquals(
            "A URL starting with / needs a Host header",
            assertFailsWith<RequestBuildException> { uri("/a") }.message,
        )
        assertEquals(
            "Only http and https URLs are supported: ftp://example.com/file",
            assertFailsWith<RequestBuildException> { uri("ftp://example.com/file") }.message,
        )
        assertEquals(
            "The URL has no host: http://under_score/a",
            assertFailsWith<RequestBuildException> { uri("http://under_score/a") }.message,
        )
        // The authority is not percent-encoded: a space there makes the URL invalid instead of hiding in %20.
        val invalid = assertFailsWith<RequestBuildException> { uri("http://exa mple.com/") }.message.orEmpty()
        assertEquals(
            "Invalid URL http://exa mple.com/: Illegal character in authority",
            invalid.substringBeforeLast(" at index"),
        )
    }

    @Test
    fun `accepts a scheme in any case and a path-less URL with a query`() {
        assertEquals("HTTP://Example.com/a", uri("HTTP://Example.com/a").toString())
        assertEquals("http://x?q=a%20b", uri("x?q=a b").toString())
        assertEquals("http://example.com", uri("example.com").toString())
        assertEquals("http://h:1/a", uri("/a", "h:1").toString())
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        delimiter = '|',
        value = [
            "%41|%41", "%4|%254", "a%4|a%254", "%|%25", "a%|a%25", "%zz|%25zz", "%4z|%254z", "%z4|%25z4",
            "%aF|%aF", "%fA|%fA", "%09|%09", "%90|%90", "%G0|%25G0", "%0G|%250G", "%/0|%25/0", "%:0|%25:0",
            "%0/|%250/", "%0:|%250:", "%`0|%25%600", "%0`|%250%60", "%@0|%25@0", "%0@|%250@", "!~|!~", "a b|a%20b",
        ],
    )
    fun `keeps real percent escapes and encodes the rest`(
        text: String,
        expected: String,
    ) {
        assertEquals(expected, RequestUrls.encode(text))
    }

    @Test
    fun `encodes the characters around the printable ASCII range and every one a URI does not allow`() {
        assertEquals("%20!~%7F", RequestUrls.encode(" !~\u007F"))
        assertEquals("%22%3C%3E%5C%5E%60%7B%7C%7D%5B%5D", RequestUrls.encode("\"<>\\^`{|}[]"))
        assertEquals("a%0Ab", RequestUrls.encode("a\nb"))
    }

    @Test
    fun `encodes a lone high surrogate at the end as a question mark and a pair as one character`() {
        assertEquals("a%3F", RequestUrls.encode("a\uD83D"))
        assertEquals("%F0%9F%98%80%F0%9F%98%80", RequestUrls.encode("😀😀"))
        assertEquals("%F0%9F%98%80x", RequestUrls.encode("😀x"))
        assertEquals("%C3%A4%E2%82%AC", RequestUrls.encode("ä€"))
    }
}
