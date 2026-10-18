package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.HighlightSpan
import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class KotlinHighlightingTest {
    private val q = "\"\"\""

    private val kotlin =
        Highlighted(
            """
            |package dev.sample
            |
            |import kotlin.math.PI
            |
            |@Suppress("unused")
            |data class Point(val x: Int = 1_000, var label: String? = null) {
            |    override fun toString(): String = "(${'$'}x)" // short
            |    val raw = ${q}first
            |second ${'$'}x $q.trimIndent()
            |    suspend fun next() = when (x) { 0 -> 0x1F; else -> 3.5f }
            |    /* block */ val c = 'c'
            |}
            |val empty = $q$q
            |val quotes = ${q}a"$q"
            |fun after() = true
            |
            """.trimMargin(),
            RSyntaxHighlighter.kotlin,
        )

    @Test
    fun keywords() {
        for (keyword in listOf(
            "package",
            "import",
            "data",
            "class",
            "val",
            "var",
            "override",
            "fun",
            "suspend",
            "when",
        )) {
            assertEquals(setOf(TokenStyle.KEYWORD), kotlin.stylesOf(keyword), keyword)
        }
        assertEquals(setOf(TokenStyle.KEYWORD), kotlin.stylesOf("null"))
        assertEquals(setOf(TokenStyle.KEYWORD), kotlin.stylesOf("true"))
    }

    @Test
    fun `raw strings over several lines, and code after them`() {
        assertEquals(setOf(TokenStyle.STRING), kotlin.stylesOf("${q}first\nsecond \$x $q"))
        assertEquals(emptySet(), kotlin.stylesOf("trimIndent"), "code after the raw string is lexed again")
        assertEquals(setOf(TokenStyle.STRING), kotlin.stylesOf("$q$q"))
        assertEquals(setOf(TokenStyle.STRING), kotlin.stylesOf("${q}a\"$q\""), "extra quotes belong to the string")
        assertEquals(setOf(TokenStyle.KEYWORD), kotlin.stylesOf("fun", occurrence = 2), "lexing recovers afterwards")
    }

    @Test
    fun `strings, comments, numbers, types and annotations`() {
        assertEquals(setOf(TokenStyle.STRING), kotlin.stylesOf("\"(\$x)\""))
        assertEquals(setOf(TokenStyle.STRING), kotlin.stylesOf("'c'"))
        assertEquals(setOf(TokenStyle.COMMENT), kotlin.stylesOf("// short"))
        assertEquals(setOf(TokenStyle.COMMENT), kotlin.stylesOf("/* block */"))
        for (number in listOf("1_000", "0x1F", "3.5f")) {
            assertEquals(setOf(TokenStyle.NUMBER), kotlin.stylesOf(number), number)
        }
        assertEquals(setOf(TokenStyle.TYPE), kotlin.stylesOf("Int"))
        assertEquals(setOf(TokenStyle.ANNOTATION), kotlin.stylesOf("@Suppress"))
        assertEquals(emptySet(), kotlin.stylesOf("Point"))
    }

    @Test
    fun `spans are well-formed`() = kotlin.assertWellFormed()

    @Test
    fun `unterminated raw string runs to the end`() {
        val text = "val s = ${q}open\nstill open"
        val spans = RSyntaxHighlighter.kotlin.highlight(text)
        assertEquals(HighlightSpan(text.indexOf(q), text.length, TokenStyle.STRING), spans.last())
    }
}
