package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test

class LatexHighlightingTest {
    private val tex =
        Highlighted(
            """
            |\documentclass[11pt]{article}
            |% a comment
            |\begin{document}
            |\section{Intro} 100\% growth, ${'$'}x^2 + \alpha${'$'} and \(a+b\). % trailing
            |\[ \int_0^1 f(x)\,dx \]
            |${'$'}${'$'} a = b ${'$'}${'$'}
            |\verb|a % b| and \cite{key}
            |\makeatletter\@ifundefined{x}{}{}\makeatother
            |\end{document}
            |
            """.trimMargin(),
            RSyntaxHighlighter.latex,
        )

    @Test
    fun `commands are keywords`() {
        tex.assertExactly(TokenStyle.KEYWORD, "\\documentclass")
        tex.assertExactly(TokenStyle.KEYWORD, "\\section")
        tex.assertExactly(TokenStyle.KEYWORD, "\\cite")
        tex.assertExactly(TokenStyle.KEYWORD, "\\@ifundefined")
        tex.assertExactly(TokenStyle.KEYWORD, "\\begin")
        tex.assertExactly(TokenStyle.KEYWORD, "\\end", occurrence = 0)
    }

    @Test
    fun `environment names are types`() {
        tex.assertExactly(TokenStyle.TYPE, "document", occurrence = 0, wholeWord = true)
        tex.assertExactly(TokenStyle.TYPE, "document", occurrence = 1, wholeWord = true)
    }

    @Test
    fun `comments start at percent signs but not at an escaped one`() {
        tex.assertExactly(TokenStyle.COMMENT, "% a comment")
        tex.assertExactly(TokenStyle.COMMENT, "% trailing")
        tex.assertPlain("\\% growth")
    }

    @Test
    fun `math is a string`() {
        tex.assertExactly(TokenStyle.STRING, "${'$'}x^2 + \\alpha${'$'}")
        tex.assertExactly(TokenStyle.STRING, "\\(a+b\\)")
        tex.assertExactly(TokenStyle.STRING, "\\[ \\int_0^1 f(x)\\,dx \\]")
        tex.assertExactly(TokenStyle.STRING, "${'$'}${'$'} a = b ${'$'}${'$'}")
    }

    @Test
    fun `verb text with a percent sign is no comment`() {
        tex.assertExactly(TokenStyle.STRING, "\\verb|a % b|")
        tex.assertPlain(" and ")
    }

    @Test
    fun `a dollar sign in verb text starts no math`() {
        val verb = Highlighted("\\verb+x${'$'}y+ and ${'$'}${'$'} x = y ${'$'}${'$'}\n", RSyntaxHighlighter.latex)
        verb.assertExactly(TokenStyle.STRING, "\\verb+x${'$'}y+")
        verb.assertPlain(" and ")
        verb.assertExactly(TokenStyle.STRING, "${'$'}${'$'} x = y ${'$'}${'$'}")
    }

    @Test
    fun `long verb text and unclosed math`() {
        val long = "a".repeat(500)
        val text = Highlighted("\\verb|$long|\n\\(unclosed ${'$'}x\n\\[ a\n", RSyntaxHighlighter.latex)
        text.assertExactly(TokenStyle.STRING, "\\verb|$long|")
        text.assertExactly(TokenStyle.STRING, "\\(unclosed ${'$'}x")
        text.assertExactly(TokenStyle.STRING, "\\[ a")
    }

    @Test
    fun `a line break with an optional argument is no display math`() {
        val spacing = Highlighted("a \\\\[2pt] b \\emph{c}\n", RSyntaxHighlighter.latex)
        spacing.assertPlain("[2pt]")
        spacing.assertExactly(TokenStyle.KEYWORD, "\\emph")
    }

    @Test
    fun `spans are well-formed`() = tex.assertWellFormed()
}
