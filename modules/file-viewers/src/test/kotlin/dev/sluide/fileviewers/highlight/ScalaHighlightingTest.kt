package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class ScalaHighlightingTest {
    private val q = "\"\"\""
    private val dollar = '$'

    private val scala =
        Highlighted(
            """
            |package app
            |enum Color derives CanEqual:
            |  case Red, Green
            |given Ordering[Int] = Ordering.Int
            |extension (s: String) def shout = s.toUpperCase
            |opaque type Id = Int
            |transparent inline def f(using ctx: Ctx): Int = 1
            |export a.b
            |object Main {
            |  val s = s"hello ${dollar}name and $dollar{x + 1}"
            |  val f1 = f"${dollar}n%05d"
            |  val r = raw"a\nb"
            |  val m = ${q}multi
            |    line "quoted" text$q.stripMargin
            |  if x then y else z // end
            |}
            |
            """.trimMargin(),
            RSyntaxHighlighter.scala,
        )

    @Test
    fun `Scala 3 keywords`() {
        for (word in listOf("given", "using", "enum", "extension", "then", "export", "opaque", "derives")) {
            assertEquals(setOf(TokenStyle.KEYWORD), scala.stylesOf(word), word)
        }
        assertEquals(setOf(TokenStyle.KEYWORD), scala.stylesOf("transparent inline"))
        assertEquals(setOf(TokenStyle.TYPE), scala.stylesOf("Int"))
    }

    @Test
    fun `interpolators belong to their string`() {
        assertEquals(setOf(TokenStyle.STRING), scala.stylesOf("s\"hello ${dollar}name and $dollar{x + 1}\""))
        assertEquals(setOf(TokenStyle.STRING), scala.stylesOf("f\"${dollar}n%05d\""))
        assertEquals(setOf(TokenStyle.STRING), scala.stylesOf("raw\"a\\nb\""))
        assertEquals(emptySet(), scala.stylesOf("shout"), "a name ending in s is no interpolator")
    }

    @Test
    fun `triple quoted strings reach over several lines`() {
        assertEquals(setOf(TokenStyle.STRING), scala.stylesOf("${q}multi\n    line \"quoted\" text$q"))
        scala.assertExactly(TokenStyle.COMMENT, "// end")
    }
}
