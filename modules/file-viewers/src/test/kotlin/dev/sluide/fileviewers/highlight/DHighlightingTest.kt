package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class DHighlightingTest {
    private val d =
        Highlighted(
            """
            |module app;
            |import std.stdio;
            |/// doc
            |void main(string[] args) {
            |    auto s = "text";
            |    int x = 0x1F + 1_000;
            |    writeln("hi", s); // comment
            |    foreach (i; 0 .. 3) {}
            |}
            |/+ nested /+ inner +/ +/
            |
            """.trimMargin(),
            RSyntaxHighlighter.d,
        )

    @Test
    fun `the lexer handles D as it is`() {
        for (word in listOf("module", "import", "void", "auto", "foreach")) {
            assertEquals(setOf(TokenStyle.KEYWORD), d.stylesOf(word), word)
        }
        d.assertExactly(TokenStyle.COMMENT, "/// doc")
        d.assertExactly(TokenStyle.COMMENT, "// comment")
        d.assertExactly(TokenStyle.STRING, "\"text\"")
        d.assertExactly(TokenStyle.NUMBER, "0x1F")
        d.assertExactly(TokenStyle.NUMBER, "1_000")
        assertEquals(setOf(TokenStyle.COMMENT), d.stylesOf("/+ nested"))
    }
}
