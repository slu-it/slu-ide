package dev.sluide.httpclient.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class TruncatedTest {
    @Test
    fun `cuts a text longer than the limit with an ellipsis`() {
        assertEquals("abcd", truncated("abcd", 4))
        assertEquals("abc…", truncated("abcde", 4))
        assertEquals("", truncated("", 4))
    }
}
