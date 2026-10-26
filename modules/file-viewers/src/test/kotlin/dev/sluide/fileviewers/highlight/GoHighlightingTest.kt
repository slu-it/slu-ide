package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class GoHighlightingTest {
    private val go =
        Highlighted(
            """
            |package main
            |
            |func π() {}
            |func Map[T any, K comparable](xs []T) error {
            |	s := ${'`'}raw
            |	multi "line" // not a comment
            |	${'`'}
            |	t := ${'`'}one line${'`'} + "x"
            |	r := "${'`'}" + 'x' // comment
            |	return nil
            |}
            |
            """.trimMargin(),
            RSyntaxHighlighter.go,
        )

    @Test
    fun `generics constraints and error are types`() {
        for (word in listOf("any", "comparable", "error")) assertEquals(setOf(TokenStyle.TYPE), go.stylesOf(word), word)
        assertEquals(setOf(TokenStyle.KEYWORD), go.stylesOf("func"))
        assertEquals(setOf(TokenStyle.KEYWORD), go.stylesOf("nil"))
    }

    @Test
    fun `raw strings in backticks reach over several lines`() {
        val bt = '`'
        assertEquals(setOf(TokenStyle.STRING), go.stylesOf("${bt}raw\n\tmulti \"line\" // not a comment\n\t$bt"))
        assertEquals(setOf(TokenStyle.STRING), go.stylesOf("${bt}one line$bt"))
        assertEquals(emptySet(), go.stylesOf("π"), "a Unicode identifier is no raw string")
        // A backtick in an ordinary string starts no raw string, and the rest of its line is still lexed.
        go.assertExactly(TokenStyle.STRING, "\"$bt\"")
        go.assertExactly(TokenStyle.STRING, "'x'")
        go.assertExactly(TokenStyle.COMMENT, "// comment")
        assertEquals(setOf(TokenStyle.KEYWORD), go.stylesOf("return"), "code after the raw string is lexed again")
    }
}
