package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class PascalHighlightingTest {
    private val pascal =
        Highlighted(
            """
            |program Demo;
            |Uses SysUtils;
            |{ block comment }
            |(* other *)
            |// line
            |VAR
            |  x: Integer;
            |  s: AnsiString;
            |  b: boolean;
            |  l: LongInt; w: UInt64;
            |{${'$'}A+}
            |Begin
            |  x := ${'$'}1F + 42;
            |  s := 'it''s';
            |  If x > 1 Then writeln(s);
            |END.
            |
            """.trimMargin(),
            RSyntaxHighlighter.pascal,
        )

    @Test
    fun `keywords and types in any case`() {
        for (word in listOf("program", "Uses", "VAR", "Begin", "If", "Then", "END")) {
            assertEquals(setOf(TokenStyle.KEYWORD), pascal.stylesOf(word), word)
        }
        for (word in listOf("Integer", "AnsiString", "boolean", "LongInt", "UInt64")) {
            assertEquals(setOf(TokenStyle.TYPE), pascal.stylesOf(word), word)
        }
    }

    @Test
    fun `comments, strings and numbers`() {
        pascal.assertExactly(TokenStyle.COMMENT, "{ block comment }")
        pascal.assertExactly(TokenStyle.COMMENT, "(* other *)")
        pascal.assertExactly(TokenStyle.COMMENT, "// line")
        assertEquals(setOf(TokenStyle.STRING), pascal.stylesOf("'it''s'"))
        pascal.assertExactly(TokenStyle.NUMBER, "42")
        pascal.assertExactly(TokenStyle.NUMBER, "${'$'}1F")
        assertEquals(setOf(TokenStyle.KEYWORD), pascal.stylesOf("${'$'}A"), "a directive is no number")
    }
}
