package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class GitattributesHighlightingTest {
    private val attributes =
        Highlighted(
            """
            |# Normalise
            |* text=auto
            |*.sh text eol=lf
            |*.png binary -diff !merge
            |docs/**/?.md linguist-documentation
            |
            """.trimMargin(),
            RSyntaxHighlighter.gitattributes,
        )

    @Test
    fun `comments`() {
        attributes.assertExactly(TokenStyle.COMMENT, "# Normalise")
    }

    @Test
    fun `wildcards of the pattern are operators`() {
        attributes.assertExactlyAt(TokenStyle.OPERATOR, attributes.find("*", 0), 1, "lone star")
        attributes.assertExactlyAt(TokenStyle.OPERATOR, attributes.find("*.sh"), 1, "star of *.sh")
        attributes.assertExactly(TokenStyle.OPERATOR, "**")
        attributes.assertExactly(TokenStyle.OPERATOR, "?")
        assertEquals(emptySet(), attributes.stylesOf(".sh"))
    }

    @Test
    fun `attributes are properties and their values plain`() {
        attributes.assertExactly(TokenStyle.PROPERTY, "text", occurrence = 0)
        attributes.assertExactly(TokenStyle.PROPERTY, "eol")
        attributes.assertPlain("=lf")
        attributes.assertExactly(TokenStyle.PROPERTY, "binary")
        attributes.assertExactly(TokenStyle.PROPERTY, "linguist-documentation")
        assertEquals(emptySet(), attributes.stylesOf("auto"))
    }

    @Test
    fun `a minus or bang before an attribute is a keyword`() {
        attributes.assertExactly(TokenStyle.KEYWORD, "-")
        attributes.assertExactly(TokenStyle.KEYWORD, "!")
        attributes.assertExactly(TokenStyle.PROPERTY, "diff")
        attributes.assertExactly(TokenStyle.PROPERTY, "merge")
    }
}
