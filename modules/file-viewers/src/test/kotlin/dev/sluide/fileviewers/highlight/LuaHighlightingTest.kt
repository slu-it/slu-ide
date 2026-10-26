package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class LuaHighlightingTest {
    private val lua =
        Highlighted(
            """
            |#!/usr/bin/env lua
            |-- comment
            |--[[ multi
            |line comment ]]
            |local x <const> = 5
            |local f <close>, g <const> = io.open("a"), 1
            |local s = [[long
            |string]]
            |local t = { a = 1, ["b"] = 2 }
            |for i = 1, 10 do
            |  if i % 2 == 0 and i < 3 then goto continue end
            |  print(#t, i // 2)
            |  ::continue::
            |end
            |function M.foo(a, ...) return a .. "x" .. 'y' end
            |local ok = nil or not false
            |
            """.trimMargin(),
            RSyntaxHighlighter.lua,
        )

    @Test
    fun `keywords, also goto`() {
        val keywords = listOf("local", "for", "do", "if", "then", "goto", "end", "function", "return", "nil", "false")
        for (keyword in keywords) {
            assertEquals(setOf(TokenStyle.KEYWORD), lua.stylesOf(keyword), keyword)
        }
        assertEquals(setOf(TokenStyle.BUILTIN), lua.stylesOf("print"))
    }

    @Test
    fun `attributes are annotations, comparisons are not`() {
        lua.assertExactly(TokenStyle.ANNOTATION, "<const>", 0)
        lua.assertExactly(TokenStyle.ANNOTATION, "<close>")
        lua.assertExactly(TokenStyle.ANNOTATION, "<const>", 1)
        assertEquals(setOf(TokenStyle.OPERATOR), lua.stylesOf("<", 3))
    }

    @Test
    fun `labels are types`() {
        lua.assertExactly(TokenStyle.TYPE, "::continue::")
    }

    @Test
    fun `long strings and comments span lines`() {
        assertEquals(setOf(TokenStyle.STRING), lua.stylesOf("[[long"))
        assertEquals(setOf(TokenStyle.STRING), lua.stylesOf("string]]"))
        assertEquals(setOf(TokenStyle.COMMENT), lua.stylesOf("--[[ multi"))
        assertEquals(setOf(TokenStyle.COMMENT), lua.stylesOf("line comment ]]"))
        assertEquals(setOf(TokenStyle.COMMENT), lua.stylesOf("-- comment"))
    }

    @Test
    fun `shebang is a comment, the length operator is not`() {
        assertEquals(setOf(TokenStyle.COMMENT), lua.stylesOf("#!/usr/bin/env lua"))
        assertEquals(setOf(TokenStyle.OPERATOR), lua.stylesOf("#t"))
    }

    @Test
    fun `spans are well-formed`() = lua.assertWellFormed()
}
