package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class CHighlightingTest {
    private val c =
        Highlighted(
            """
            |#include <stdio.h>
            |#define MAX(a, b) ((a) > (b) ? (a) : (b))
            |// line comment
            |/* block */
            |static_assert(sizeof(int) == 4, "int");
            |constexpr int N = 0x1F;
            |typedef struct { bool ok; alignas(16) float v[4]; } point_t;
            |int main(void) {
            |    char *s = "hi\n"; char ch = 'x';
            |    bool b = true && !false;
            |    int *p = nullptr;
            |    _Atomic int a = 1'000'000;
            |    return NULL == p;
            |}
            |
            """.trimMargin(),
            RSyntaxHighlighter.c,
        )

    @Test
    fun `preprocessor lines, comments, strings and numbers`() {
        assertEquals(setOf(TokenStyle.KEYWORD), c.stylesOf("#include"))
        assertEquals(setOf(TokenStyle.STRING), c.stylesOf("<stdio.h>"))
        assertEquals(setOf(TokenStyle.KEYWORD), c.stylesOf("#define"))
        c.assertExactly(TokenStyle.COMMENT, "// line comment")
        c.assertExactly(TokenStyle.COMMENT, "/* block */")
        c.assertExactly(TokenStyle.STRING, "\"hi\\n\"")
        c.assertExactly(TokenStyle.STRING, "'x'")
        c.assertExactly(TokenStyle.NUMBER, "0x1F")
    }

    @Test
    fun `C11 and C23 keywords and types the lexer misses`() {
        for (word in listOf("static_assert", "constexpr", "alignas", "true", "false", "nullptr", "NULL", "_Atomic")) {
            assertEquals(setOf(TokenStyle.KEYWORD), c.stylesOf(word), word)
        }
        assertEquals(setOf(TokenStyle.TYPE), c.stylesOf("bool"))
    }

    @Test
    fun `a digit separator does not start a character literal`() {
        assertEquals(setOf(TokenStyle.NUMBER), c.stylesOf("1'000'000"))
        assertEquals(setOf(TokenStyle.KEYWORD), c.stylesOf("return"), "the next line is lexed normally")
        assertEquals(setOf(TokenStyle.STRING), c.stylesOf("'x'"), "character literals still work")
    }
}
