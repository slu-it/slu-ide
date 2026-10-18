package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class IgnoreFileHighlightingTest {
    private val ignore =
        Highlighted(
            """
            |# build output *.class
            |build/
            |*.log
            |!keep.log
            |docs/**/draft?.md
            |\#literal
            |file\*name
            |not # a comment
            |
            """.trimMargin(),
            RSyntaxHighlighter.ignoreFile,
        )

    @Test
    fun `comments only at the start of a line`() {
        assertEquals(setOf(TokenStyle.COMMENT), ignore.stylesOf("# build output *.class"))
        assertEquals(emptySet(), ignore.stylesOf("\\#literal"))
        assertEquals(emptySet(), ignore.stylesOf("not # a comment"))
    }

    @Test
    fun `negations and wildcards`() {
        assertEquals(setOf(TokenStyle.KEYWORD), ignore.stylesOf("!"))
        assertEquals(setOf(TokenStyle.OPERATOR), ignore.stylesOf("*", occurrence = 1))
        assertEquals(setOf(TokenStyle.OPERATOR), ignore.stylesOf("**"))
        assertEquals(setOf(TokenStyle.OPERATOR), ignore.stylesOf("?"))
        assertEquals(emptySet(), ignore.stylesOf("\\*"), "an escaped star is no wildcard")
        assertEquals(emptySet(), ignore.stylesOf("build/"))
    }

    @Test
    fun `fenced ignore files are highlighted`() =
        assertSame(RSyntaxHighlighter.ignoreFile, RSyntaxHighlighter.forLanguage("gitignore"))

    @Test
    fun `spans are well-formed`() = ignore.assertWellFormed()
}
