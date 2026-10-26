package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class GoModHighlightingTest {
    private val mod =
        Highlighted(
            """
            |// Module file
            |module example.com/app
            |
            |go 1.22
            |toolchain go1.22.1
            |
            |require (
            |	golang.org/x/text v0.14.0 // indirect
            |	github.com/a/b v1.2.3-rc.1+incompatible
            |)
            |
            |replace github.com/a/b => ../b
            |exclude github.com/c/d v1.0.0
            |retract [v1.0.0, v1.0.1] // broken
            |use ./app
            |
            """.trimMargin(),
            RSyntaxHighlighter.goMod,
        )

    @Test
    fun `directives at the start of a line are keywords`() {
        for (word in listOf("module", "go", "toolchain", "require", "replace", "exclude", "retract", "use")) {
            mod.assertExactly(TokenStyle.KEYWORD, word, wholeWord = true)
        }
        assertEquals(emptySet(), mod.stylesOf("golang.org/x/text"), "a path starting with go is no directive")
        assertEquals(emptySet(), mod.stylesOf("example.com/app"))
    }

    @Test
    fun `versions are numbers, arrows operators`() {
        mod.assertExactly(TokenStyle.NUMBER, "1.22", wholeWord = true)
        mod.assertExactly(TokenStyle.NUMBER, "go1.22.1")
        mod.assertExactly(TokenStyle.NUMBER, "v0.14.0")
        mod.assertExactly(TokenStyle.NUMBER, "v1.2.3-rc.1+incompatible")
        mod.assertExactly(TokenStyle.NUMBER, "v1.0.0", occurrence = 0, wholeWord = true)
        mod.assertExactly(TokenStyle.OPERATOR, "=>")
    }

    @Test
    fun `comments are whole lines or line ends and swallow what is in them`() {
        mod.assertExactly(TokenStyle.COMMENT, "// Module file")
        mod.assertExactly(TokenStyle.COMMENT, "// indirect")
        mod.assertExactly(TokenStyle.COMMENT, "// broken")
    }
}
