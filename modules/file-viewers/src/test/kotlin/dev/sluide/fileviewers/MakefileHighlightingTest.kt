package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MakefileHighlightingTest {
    private val d = "$"

    private val makefile =
        Highlighted(
            """
            |# build everything
            |CC ?= gcc
            |CFLAGS := -O2
            |OBJ = $d(SRC:.c=.o)
            |include common.mk
            |.PHONY: all clean
            |all: app
            |app: $d(OBJ)
            |${'\t'}$d(CC) $d(CFLAGS) -o $d@ $d^ "quoted"
            |%.o: %.c
            |${'\t'}@echo 'compiling $d<' # note
            |ifeq ($d(OS),Windows_NT)
            |export PATH
            |endif
            |clean:
            |${'\t'}rm -f $d{OBJ} 42
            |
            """.trimMargin(),
            RSyntaxHighlighter.makefile,
        )

    @Test
    fun `targets are types`() {
        for (target in listOf(".PHONY:", "all:", "app:", "%.o:", "clean:")) {
            assertEquals(setOf(TokenStyle.TYPE), makefile.stylesOf(target), target)
        }
        assertEquals(emptySet(), makefile.stylesOf("CFLAGS"), "an assignment with := is no rule")
    }

    @Test
    fun `directives, conditionals and assignments`() {
        for (keyword in listOf("include", "ifeq", "export", "endif")) {
            assertEquals(setOf(TokenStyle.KEYWORD), makefile.stylesOf(keyword), keyword)
        }
        assertEquals(setOf(TokenStyle.OPERATOR), makefile.stylesOf("?="))
        assertEquals(setOf(TokenStyle.OPERATOR), makefile.stylesOf(":="))
    }

    @Test
    fun `variables, also automatic ones`() {
        for (variable in listOf("$d(SRC:.c=.o)", "$d(CC)", "$d{OBJ}", "$d@", "$d^")) {
            assertEquals(setOf(TokenStyle.ANNOTATION), makefile.stylesOf(variable), variable)
        }
        assertEquals(setOf(TokenStyle.STRING), makefile.stylesOf("'compiling $d<'"))
    }

    @Test
    fun `comments, strings and numbers`() {
        assertEquals(setOf(TokenStyle.COMMENT), makefile.stylesOf("# build everything"))
        assertEquals(setOf(TokenStyle.COMMENT), makefile.stylesOf("# note"))
        assertEquals(setOf(TokenStyle.STRING), makefile.stylesOf("\"quoted\""))
        assertEquals(setOf(TokenStyle.NUMBER), makefile.stylesOf("42"))
    }

    @Test
    fun `fenced Makefiles are highlighted`() {
        for (name in listOf("makefile", "make")) {
            assertSame(RSyntaxHighlighter.makefile, RSyntaxHighlighter.forLanguage(name), name)
        }
    }

    @Test
    fun `spans are well-formed`() = makefile.assertWellFormed()
}
