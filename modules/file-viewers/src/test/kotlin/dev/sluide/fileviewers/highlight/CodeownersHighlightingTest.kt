package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class CodeownersHighlightingTest {
    private val owners =
        Highlighted(
            """
            |# Owners
            |* @global-owner1 @org/team-a
            |/docs/**/*.md docs@example.com # trailing
            |/build/logs/ @doctocat
            |*.js?x @js-owner
            |
            """.trimMargin(),
            RSyntaxHighlighter.codeowners,
        )

    @Test
    fun `comments`() {
        owners.assertExactly(TokenStyle.COMMENT, "# Owners")
        owners.assertExactly(TokenStyle.COMMENT, "# trailing")
    }

    @Test
    fun `wildcards of the pattern are operators`() {
        owners.assertExactlyAt(TokenStyle.OPERATOR, owners.find("*", 0), 1, "lone star")
        owners.assertExactly(TokenStyle.OPERATOR, "**")
        owners.assertExactlyAt(TokenStyle.OPERATOR, owners.find("*.md"), 1, "star before .md")
        owners.assertExactly(TokenStyle.OPERATOR, "?")
        assertEquals(emptySet(), owners.stylesOf("/build/logs/"))
    }

    @Test
    fun `owners are annotations`() {
        owners.assertExactly(TokenStyle.ANNOTATION, "@global-owner1")
        owners.assertExactly(TokenStyle.ANNOTATION, "@org/team-a")
        owners.assertExactly(TokenStyle.ANNOTATION, "docs@example.com")
        owners.assertExactly(TokenStyle.ANNOTATION, "@doctocat")
    }
}
