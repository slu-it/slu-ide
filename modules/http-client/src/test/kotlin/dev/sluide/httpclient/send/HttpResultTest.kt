package dev.sluide.httpclient.send

import dev.sluide.httpclient.parse.HttpVersion
import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpResultTest {
    private fun result(vararg headers: Pair<String, String>) =
        HttpResult(
            200,
            "OK",
            HttpVersion.HTTP_1_1,
            URI("http://example.com/"),
            headers.toList(),
            ByteArray(0),
            false,
            Duration.ZERO,
            emptyList(),
        )

    @Test
    fun `finds a header by name in any case, the first one when it is sent twice`() {
        val result = result("X-Other" to "o", "content-TYPE" to "text/plain", "Content-Type" to "second")

        assertEquals("text/plain", result.header("Content-Type"))
        assertEquals("text/plain", result.header("CONTENT-TYPE"))
        assertEquals("o", result.header("x-other"))
        assertNull(result.header("Content-Length"))
    }
}
