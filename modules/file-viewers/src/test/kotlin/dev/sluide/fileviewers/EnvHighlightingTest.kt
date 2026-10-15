package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class EnvHighlightingTest {
    private val env =
        Highlighted(
            """
            |# Local settings
            |API_KEY=abc123 # trailing
            |export TOKEN=xyz
            |QUOTED="a b # c"
            |SINGLE='x y'
            |URL=https://x.y/z?a=b#frag # docs
            |PASSWORD=pa#ss
            |PORT=8080
            |
            """.trimMargin(),
            RSyntaxHighlighter.env,
        )

    @Test
    fun `keys are properties, values strings`() {
        env.assertWellFormed()
        for (key in listOf("API_KEY", "TOKEN", "QUOTED", "SINGLE", "URL")) {
            assertEquals(setOf(TokenStyle.PROPERTY), env.stylesOf(key), key)
        }
        for (value in listOf("abc123", "xyz", "\"a b # c\"", "'x y'", "https://x.y/z?a=b")) {
            assertEquals(setOf(TokenStyle.STRING), env.stylesOf(value), value)
        }
        assertEquals(setOf(TokenStyle.KEYWORD), env.stylesOf("export"))
        assertEquals(setOf(TokenStyle.NUMBER), env.stylesOf("8080"))
    }

    @Test
    fun `comments start at a hash after a blank only`() {
        assertEquals(setOf(TokenStyle.COMMENT), env.stylesOf("# Local settings"))
        assertEquals(setOf(TokenStyle.COMMENT), env.stylesOf("# trailing"))
        assertEquals(setOf(TokenStyle.COMMENT), env.stylesOf("# docs"))
        assertEquals(setOf(TokenStyle.STRING), env.stylesOf("#frag"), "a fragment is part of the value")
        assertEquals(setOf(TokenStyle.STRING), env.stylesOf("pa#ss"))
    }

    @Test
    fun `fenced dotenv code is highlighted`() {
        assertSame(RSyntaxHighlighter.env, RSyntaxHighlighter.forLanguage("dotenv"))
        assertSame(RSyntaxHighlighter.env, RSyntaxHighlighter.forLanguage("env"))
    }
}
