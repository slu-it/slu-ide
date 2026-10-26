package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class TclHighlightingTest {
    private val d = "$"

    private val tcl =
        Highlighted(
            """
            |#!/usr/bin/env tclsh
            |# comment ${d}notvar
            |set name "World"
            |set ::env(FOO) $d{name}_x
            |proc greet {who} {
            |    puts "Hello, ${d}who! ${d}arr(key) \${d}escaped"
            |    if {${d}who eq "x"} { return $d::ns::value }
            |    namespace eval foo { variable x 1 }
            |}
            |puts ${d}name
            |
            """.trimMargin(),
            RSyntaxHighlighter.tcl,
        )

    @Test
    fun `keywords`() {
        for (keyword in listOf("set", "proc", "puts", "if", "return", "namespace", "variable", "eq")) {
            assertEquals(setOf(TokenStyle.KEYWORD), tcl.stylesOf(keyword), keyword)
        }
    }

    @Test
    fun `variables are annotations`() {
        for (variable in listOf("$d{name}", "${d}who", "${d}arr(key)", "$d::ns::value", "${d}name")) {
            tcl.assertExactly(TokenStyle.ANNOTATION, variable)
        }
    }

    @Test
    fun `variables inside double-quoted strings`() {
        assertEquals(setOf(TokenStyle.STRING, TokenStyle.ANNOTATION), tcl.stylesOf("\"Hello, ${d}who! "))
        assertEquals(setOf(TokenStyle.STRING), tcl.stylesOf("Hello,"))
    }

    @Test
    fun `an escaped dollar and comments are no variables`() {
        assertEquals(setOf(TokenStyle.COMMENT), tcl.stylesOf("# comment ${d}notvar"))
        assertEquals(setOf(TokenStyle.COMMENT), tcl.stylesOf("#!/usr/bin/env tclsh"))
        assertEquals(setOf(TokenStyle.STRING), tcl.stylesOf("\\${d}escaped"))
    }

    @Test
    fun `spans are well-formed`() = tcl.assertWellFormed()
}
