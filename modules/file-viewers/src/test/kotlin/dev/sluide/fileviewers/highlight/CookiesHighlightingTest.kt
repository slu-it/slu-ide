package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class CookiesHighlightingTest {
    private val cookies =
        Highlighted(
            "# domain\tpath\tname\tvalue\tdate\n" +
                "localhost\t/\tJSESSIONID\tabc123\t-1\n" +
                "example.com\t/api\ttheme\t\"dark\"\tFri, 01 Jan 2027 00:00:00 GMT\n",
            RSyntaxHighlighter.cookies,
        )

    @Test
    fun `the header is a comment in the source`() {
        assertEquals(setOf(TokenStyle.COMMENT), cookies.stylesOf("# domain\tpath\tname\tvalue\tdate"))
        assertEquals(emptySet(), cookies.stylesOf("localhost"))
    }
}
