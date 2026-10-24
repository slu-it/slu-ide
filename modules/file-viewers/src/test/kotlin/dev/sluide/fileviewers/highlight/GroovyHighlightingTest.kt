package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class GroovyHighlightingTest {
    private val t = "'''"
    private val d = "\"\"\""

    private val groovy =
        Highlighted(
            """
            |plugins {
            |    id 'java'
            |    id "org.springframework.boot" version "3.2.0"
            |}
            |// line comment
            |/* block */
            |def version = "1.0-${'$'}{project.name}"
            |def multi = ${t}first
            |second$t
            |def gmulti = ${d}hello ${'$'}{x}
            |world$d
            |def slashy = /a.*b/
            |tasks.register('hello') {
            |    doLast { println "hi" }
            |}
            |if (true) { int n = 42 } else { return null }
            |trait T { var v; record R() }
            |def after = 1
            |
            """.trimMargin(),
            RSyntaxHighlighter.groovy,
        )

    @Test
    fun keywords() {
        for (keyword in listOf("def", "if", "else", "return", "null", "true", "trait", "var", "record")) {
            assertEquals(setOf(TokenStyle.KEYWORD), groovy.stylesOf(keyword), keyword)
        }
        assertEquals(setOf(TokenStyle.TYPE), groovy.stylesOf("int", occurrence = 1))
        assertEquals(setOf(TokenStyle.NUMBER), groovy.stylesOf("42"))
    }

    @Test
    fun `quoted strings and a GString, with the DSL's code around them`() {
        assertEquals(setOf(TokenStyle.STRING), groovy.stylesOf("'java'"))
        assertEquals(setOf(TokenStyle.STRING), groovy.stylesOf("\"org.springframework.boot\""))
        assertEquals(setOf(TokenStyle.STRING), groovy.stylesOf("\"1.0-"))
        assertEquals(emptySet(), groovy.stylesOf("project.name"), "the interpolated code is not a string")
        assertEquals(emptySet(), groovy.stylesOf("plugins"))
        assertEquals(emptySet(), groovy.stylesOf("version"))
    }

    @Test
    fun `triple-quoted strings over several lines, and code after them`() {
        assertEquals(setOf(TokenStyle.STRING), groovy.stylesOf("${t}first\nsecond$t"))
        assertEquals(setOf(TokenStyle.STRING), groovy.stylesOf("${d}hello "))
        assertEquals(setOf(TokenStyle.STRING), groovy.stylesOf("world$d"))
        assertEquals(setOf(TokenStyle.KEYWORD), groovy.stylesOf("def", occurrence = 4), "lexing recovers afterwards")
    }

    @Test
    fun `slashy strings and comments`() {
        assertEquals(setOf(TokenStyle.STRING), groovy.stylesOf("/a.*b/"))
        assertEquals(setOf(TokenStyle.COMMENT), groovy.stylesOf("// line comment"))
        assertEquals(setOf(TokenStyle.COMMENT), groovy.stylesOf("/* block */"))
    }

    @Test
    fun `spans are well-formed`() = groovy.assertWellFormed()
}
