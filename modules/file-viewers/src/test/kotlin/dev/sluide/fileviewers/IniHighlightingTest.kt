package dev.sluide.fileviewers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class IniHighlightingTest {
    private val ini =
        Highlighted(
            """
            |; settings
            |# also a comment
            |[database]
            |host = localhost
            |port=5432 ; trailing
            |[remote "origin"]
            |  url = https://example.com/repo.git
            |[options]
            |install_requires =
            |    requests>=2.0
            |    flask
            |name: value with = sign later
            |timeout: 30
            |
            """.trimMargin(),
            RSyntaxHighlighter.ini,
        )

    @Test
    fun `sections are types, keys properties`() {
        assertEquals(setOf(TokenStyle.TYPE), ini.stylesOf("[database]"))
        assertEquals(setOf(TokenStyle.TYPE), ini.stylesOf("[remote \"origin\"]"))
        for (key in listOf("host", "port", "url", "install_requires", "timeout")) {
            assertEquals(setOf(TokenStyle.PROPERTY), ini.stylesOf(key), key)
        }
        assertEquals(setOf(TokenStyle.PROPERTY), ini.stylesOf("name: value with"), "the key goes up to the =")
    }

    @Test
    fun `values and continued values stay plain`() {
        assertEquals(emptySet(), ini.stylesOf("localhost"))
        assertEquals(emptySet(), ini.stylesOf("requests"), "a >= is no key separator")
        assertEquals(emptySet(), ini.stylesOf("flask"))
        assertEquals(setOf(TokenStyle.OPERATOR), ini.stylesOf("=", occurrence = 0))
    }

    @Test
    fun `comments`() {
        assertEquals(setOf(TokenStyle.COMMENT), ini.stylesOf("; settings"))
        assertEquals(setOf(TokenStyle.COMMENT), ini.stylesOf("# also a comment"))
        assertEquals(setOf(TokenStyle.COMMENT), ini.stylesOf("; trailing"))
    }

    @Test
    fun `fenced ini is highlighted`() = assertSame(RSyntaxHighlighter.ini, RSyntaxHighlighter.forLanguage("ini"))

    @Test
    fun `spans are well-formed`() = ini.assertWellFormed()
}
