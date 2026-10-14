package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertEquals

class CssHighlightingTest {
    private val css =
        Highlighted(
            """
            |/* layout */
            |.btn:hover > a, #main { color: #fff; margin: 0 1.5rem !important; }
            |@media (min-width: 600px) {
            |  .card { --gap: 4px; padding: var(--gap); }
            |}
            |.nav { background: url(http://example.com/a.png); content: "--not-a-property"; }
            |@supports (display: grid) { @layer base { .grid { display: grid; } } }
            |
            """.trimMargin(),
            RSyntaxHighlighter.css,
        )

    @Test
    fun `selectors, properties and at-rules`() {
        assertEquals(setOf(TokenStyle.TYPE), css.stylesOf(".btn"))
        assertEquals(setOf(TokenStyle.KEYWORD), css.stylesOf(":hover"))
        assertEquals(setOf(TokenStyle.ANNOTATION), css.stylesOf("#main"))
        assertEquals(setOf(TokenStyle.KEYWORD), css.stylesOf("color"))
        assertEquals(setOf(TokenStyle.KEYWORD), css.stylesOf("@media"))
        assertEquals(setOf(TokenStyle.KEYWORD), css.stylesOf("!important"))
        assertEquals(setOf(TokenStyle.KEYWORD), css.stylesOf("@supports"))
        assertEquals(setOf(TokenStyle.KEYWORD), css.stylesOf("@layer"))
    }

    @Test
    fun `values, custom properties and comments`() {
        assertEquals(setOf(TokenStyle.NUMBER), css.stylesOf("#fff"))
        assertEquals(setOf(TokenStyle.NUMBER), css.stylesOf("600px"))
        assertEquals(setOf(TokenStyle.ANNOTATION), css.stylesOf("--gap"))
        assertEquals(setOf(TokenStyle.ANNOTATION), css.stylesOf("--gap", 1))
        assertEquals(setOf(TokenStyle.BUILTIN), css.stylesOf("var"))
        assertEquals(setOf(TokenStyle.COMMENT), css.stylesOf("/* layout */"))
        assertEquals(setOf(TokenStyle.STRING), css.stylesOf("\"--not-a-property\""))
        // An unquoted URL is no `//` comment.
        assertEquals(emptySet(), css.stylesOf("//example.com"))
    }

    @Test
    fun `spans are well-formed`() = css.assertWellFormed()
}
