package dev.sluide.gitlog.operations

import kotlin.test.Test
import kotlin.test.assertEquals

class GitMessageSizeTest {
    @Test
    fun `the area is as wide as the longest line, tabs counted, and as high as its lines`() {
        val message =
            "error: Your local changes to the following files would be overwritten by checkout:\n\tfile.txt\n" +
                "Please commit your changes or stash them before you switch branches.\nAborting"
        assertEquals(82 to 4, gitMessageSize(message))
    }

    @Test
    fun `a long line wraps into more rows, a long message scrolls beyond a dozen rows`() {
        val long = "x".repeat(150)
        assertEquals(
            100 to 4,
            gitMessageSize("$long\nshort"),
            "two rows for the long line, one for the short, one to spare",
        )
        assertEquals(40 to 12, gitMessageSize((1..30).joinToString("\n") { "line $it" }))
    }

    @Test
    fun `a short or empty message keeps the minimum size`() {
        assertEquals(40 to 2, gitMessageSize(""))
        assertEquals(40 to 2, gitMessageSize("one line"))
    }
}
