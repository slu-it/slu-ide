package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class ShellHighlightingTest {
    private val shell =
        Highlighted(
            """
            |#!/usr/bin/env bash
            |# greet everyone
            |set -euo pipefail
            |NAME="world ${'$'}{USER}"
            |function greet() {
            |  local count=42
            |  if [[ -f "${'$'}1" ]]; then echo "Hello ${'$'}NAME" 'single' `date`; fi # done
            |  source ./lib.sh
            |}
            |
            """.trimMargin(),
            RSyntaxHighlighter.shell,
        )

    @Test
    fun `keywords and builtins`() {
        for (keyword in listOf("function", "if", "then", "fi")) {
            assertEquals(setOf(TokenStyle.KEYWORD), shell.stylesOf(keyword), keyword)
        }
        for (builtin in listOf("set", "local", "echo", "source")) {
            assertEquals(setOf(TokenStyle.BUILTIN), shell.stylesOf(builtin), builtin)
        }
    }

    @Test
    fun `variables, also inside strings`() {
        assertEquals(setOf(TokenStyle.ANNOTATION), shell.stylesOf("NAME"))
        assertEquals(setOf(TokenStyle.ANNOTATION), shell.stylesOf("\${USER}"))
        assertEquals(setOf(TokenStyle.ANNOTATION), shell.stylesOf("\$NAME"))
        assertEquals(setOf(TokenStyle.ANNOTATION), shell.stylesOf("\$1"))
        assertEquals(setOf(TokenStyle.STRING), shell.stylesOf("\"Hello "))
    }

    @Test
    fun `shebang, comments, strings and numbers`() {
        assertEquals(setOf(TokenStyle.COMMENT), shell.stylesOf("#!/usr/bin/env bash"))
        assertEquals(setOf(TokenStyle.COMMENT), shell.stylesOf("# greet everyone"))
        assertEquals(setOf(TokenStyle.COMMENT), shell.stylesOf("# done"))
        assertEquals(setOf(TokenStyle.STRING), shell.stylesOf("'single'"))
        assertEquals(setOf(TokenStyle.STRING), shell.stylesOf("`date`"))
        assertEquals(setOf(TokenStyle.NUMBER), shell.stylesOf("42"))
    }

    @Test
    fun `spans are well-formed`() = shell.assertWellFormed()
}
