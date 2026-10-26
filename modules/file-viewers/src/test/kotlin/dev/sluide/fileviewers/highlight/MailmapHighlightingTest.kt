package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class MailmapHighlightingTest {
    private val mailmap =
        Highlighted(
            """
            |# Contributors
            |Jane Doe <jane@example.com>
            |Jane Doe <jane@example.com> <old@example.org>
            |Jane Doe <jane@example.com> J. Doe <jd@example.net>
            |
            """.trimMargin(),
            RSyntaxHighlighter.mailmap,
        )

    @Test
    fun `comments`() {
        mailmap.assertExactly(TokenStyle.COMMENT, "# Contributors")
    }

    @Test
    fun `e-mail addresses are strings, names plain`() {
        mailmap.assertExactly(TokenStyle.STRING, "<jane@example.com>", occurrence = 0)
        mailmap.assertExactly(TokenStyle.STRING, "<old@example.org>")
        mailmap.assertExactly(TokenStyle.STRING, "<jd@example.net>")
        assertEquals(emptySet(), mailmap.stylesOf("Jane Doe", occurrence = 1))
        assertEquals(emptySet(), mailmap.stylesOf("J. Doe"))
    }
}
