package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertFalse

class HandlebarsHighlightingTest {
    private val hbs =
        Highlighted(
            """
            |<div class="entry">
            |  {{! simple }}
            |  {{!-- a {{comment}} --}}
            |  {{#if author}}
            |    <h1>{{firstName}}</h1>{{{raw}}}
            |  {{else if editor}}
            |    <b>{{> partial name="x"}}</b>
            |  {{else}}
            |    {{~lookup foo "bar"~}}
            |  {{/if}}
            |  {{#each items}}{{@index}} {{this}} {{thisIsNot}}{{/each}}
            |  {{#> layout}}x{{/layout}}
            |<Ui::Card @size="s">x</Ui::Card>
            |</div>
            |
            """.trimMargin(),
            RSyntaxHighlighter.handlebars,
        )

    @Test
    fun `block helpers and else are keywords`() {
        hbs.assertExactly(TokenStyle.KEYWORD, "#if")
        hbs.assertExactly(TokenStyle.KEYWORD, "/if")
        hbs.assertExactly(TokenStyle.KEYWORD, "#each")
        hbs.assertExactly(TokenStyle.KEYWORD, "/each")
        hbs.assertExactly(TokenStyle.KEYWORD, "else", occurrence = 0)
        hbs.assertExactly(TokenStyle.KEYWORD, "else", occurrence = 1)
    }

    @Test
    fun `partials have a keyword and a type`() {
        hbs.assertExactlyAt(TokenStyle.KEYWORD, hbs.find("> partial"), 1, "partial marker")
        hbs.assertExactly(TokenStyle.TYPE, "partial")
        hbs.assertExactly(TokenStyle.KEYWORD, "#>")
        hbs.assertExactly(TokenStyle.TYPE, "layout")
    }

    @Test
    fun `delimiters are operators, also of a triple stash`() {
        hbs.assertExactly(TokenStyle.OPERATOR, "{{{")
        hbs.assertExactly(TokenStyle.OPERATOR, "}}}")
        hbs.assertExactlyAt(TokenStyle.OPERATOR, hbs.find("{{#if"), 2, "double stash")
    }

    @Test
    fun `comments`() {
        hbs.assertExactly(TokenStyle.COMMENT, "{{! simple }}")
        hbs.assertExactly(TokenStyle.COMMENT, "{{!-- a {{comment}} --}}")
    }

    @Test
    fun `data variables and this inside a mustache`() {
        hbs.assertExactly(TokenStyle.ANNOTATION, "@index")
        hbs.assertExactly(TokenStyle.KEYWORD, "this", wholeWord = true)
        hbs.assertPlain("thisIsNot")
    }

    @Test
    fun `html around the mustaches`() {
        hbs.assertExactly(TokenStyle.TAG, "div")
        hbs.assertExactly(TokenStyle.STRING, "\"entry\"")
    }

    @Test
    fun `angle-bracket components are tags`() {
        hbs.assertExactly(TokenStyle.TAG, "<Ui::Card")
        hbs.assertExactly(TokenStyle.TAG, "</Ui::Card")
    }

    @Test
    fun `a comparison in an inline script is no component`() {
        val script = Highlighted("<script>if (a <B) { x = i<N; }</script>\n", RSyntaxHighlighter.handlebars)
        assertFalse(TokenStyle.TAG in script.stylesOf("<B"))
        assertFalse(TokenStyle.TAG in script.stylesOf("<N"))
    }

    @Test
    fun `spans are well-formed`() = hbs.assertWellFormed()
}
