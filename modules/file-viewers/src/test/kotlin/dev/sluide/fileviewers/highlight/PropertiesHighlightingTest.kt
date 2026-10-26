package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class PropertiesHighlightingTest {
    private val props =
        Highlighted(
            """
            |# comment
            |! bang comment
            |spring.datasource.url=jdbc:h2:mem:db
            |server.port: 8080
            |name = true
            |path = ${'$'}{user.home}/x
            |long = a \
            |  continued=1 true
            |uni = ${'\\'}u00e4 x
            |
            """.trimMargin(),
            RSyntaxHighlighter.properties,
        )

    @Test
    fun `keys are properties, separators operators`() {
        props.assertExactly(TokenStyle.PROPERTY, "spring.datasource.url")
        props.assertExactly(TokenStyle.PROPERTY, "server.port")
        props.assertExactly(TokenStyle.PROPERTY, "name")
        props.assertExactly(TokenStyle.OPERATOR, "=", occurrence = 0)
        assertEquals(setOf(TokenStyle.OPERATOR), props.stylesOf(": 8080"))
    }

    @Test
    fun `values stay plain`() {
        props.assertPlain("jdbc:h2:mem:db")
        props.assertPlain("8080")
        props.assertPlain("true", occurrence = 0)
        props.assertPlain("/x")
        props.assertPlain("u00e4 x")
    }

    @Test
    fun `placeholders are annotations`() {
        props.assertExactly(TokenStyle.ANNOTATION, "${'$'}{user.home}")
    }

    @Test
    fun `comments with hash and bang`() {
        props.assertExactly(TokenStyle.COMMENT, "# comment")
        props.assertExactly(TokenStyle.COMMENT, "! bang comment")
    }

    @Test
    fun `a continued value is no key`() {
        assertEquals(emptySet(), props.stylesOf("continued=1 true"))
    }
}
