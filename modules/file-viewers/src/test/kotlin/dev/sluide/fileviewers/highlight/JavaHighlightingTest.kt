package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class JavaHighlightingTest {
    private val java =
        Highlighted(
            """
            |package dev.sample;
            |
            |import java.util.List;
            |
            |/** Docs with {@code x}. */
            |@Deprecated
            |public sealed interface Shape permits Circle {
            |    int SIDES = 1_000; // constant
            |    String TEXT = ${"\"\"\""}
            |        text block
            |        ${"\"\"\""};
            |    default boolean m(Object o) { return o instanceof String s && s.length() > 0x1F + 3.5d && 'c' != null; }
            |}
            |record Circle(double r) implements Shape {}
            |
            """.trimMargin(),
            RSyntaxHighlighter.java,
        )

    @Test
    fun keywords() {
        for (keyword in listOf("package", "import", "public", "sealed", "interface", "permits", "return", "record")) {
            assertEquals(setOf(TokenStyle.KEYWORD), java.stylesOf(keyword), keyword)
        }
        assertEquals(setOf(TokenStyle.KEYWORD), java.stylesOf("instanceof"))
        assertEquals(setOf(TokenStyle.KEYWORD), java.stylesOf("null"))
    }

    @Test
    fun `strings including text blocks, comments, numbers, types and annotations`() {
        assertEquals(setOf(TokenStyle.STRING), java.stylesOf("text block"))
        assertEquals(setOf(TokenStyle.STRING), java.stylesOf("'c'"))
        assertEquals(setOf(TokenStyle.COMMENT), java.stylesOf("// constant"))
        assertEquals(setOf(TokenStyle.COMMENT), java.stylesOf("/** Docs with"))
        for (number in listOf("1_000", "0x1F", "3.5d")) {
            assertEquals(setOf(TokenStyle.NUMBER), java.stylesOf(number), number)
        }
        assertEquals(setOf(TokenStyle.TYPE), java.stylesOf("int "))
        assertEquals(setOf(TokenStyle.ANNOTATION), java.stylesOf("@Deprecated"))
        assertEquals(emptySet(), java.stylesOf("Shape"))
    }

    @Test
    fun `spans are well-formed`() = java.assertWellFormed()
}
