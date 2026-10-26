package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test

class DtdHighlightingTest {
    private val dtd =
        Highlighted(
            """
            |<?xml version="1.0"?>
            |<!-- comment -->
            |<!ELEMENT note (to, body+)>
            |<!ELEMENT to (#PCDATA)>
            |<!ELEMENT br EMPTY>
            |<!ELEMENT ID (#PCDATA)>
            |<!ATTLIST note id ID #REQUIRED lang CDATA #IMPLIED kind (a|b) "a" v CDATA #FIXED "1">
            |<!ENTITY copy "&#169;">
            |<!ENTITY % common SYSTEM "common.ent">
            |%common;
            |<![INCLUDE[ <!ELEMENT x ANY> ]]>
            |<!NOTATION gif SYSTEM "image/gif">
            |
            """.trimMargin(),
            RSyntaxHighlighter.dtd,
        )

    @Test
    fun `declarations are annotations like an inline DTD in XML`() {
        for (declaration in listOf("<!ELEMENT", "<!ATTLIST", "<!ENTITY", "<!NOTATION")) {
            dtd.assertExactly(TokenStyle.ANNOTATION, declaration)
        }
        dtd.assertExactly(TokenStyle.ANNOTATION, "<?xml version=\"1.0\"?>")
        dtd.assertExactly(TokenStyle.ANNOTATION, "<![INCLUDE[")
        dtd.assertExactly(TokenStyle.ANNOTATION, "]]>")
        dtd.assertExactly(TokenStyle.COMMENT, "<!-- comment -->")
    }

    @Test
    fun `keywords`() {
        dtd.assertExactly(TokenStyle.KEYWORD, "#PCDATA")
        dtd.assertExactly(TokenStyle.KEYWORD, "#REQUIRED")
        dtd.assertExactly(TokenStyle.KEYWORD, "#IMPLIED")
        dtd.assertExactly(TokenStyle.KEYWORD, "#FIXED")
        dtd.assertExactly(TokenStyle.KEYWORD, "EMPTY")
        dtd.assertExactly(TokenStyle.KEYWORD, "ANY")
        dtd.assertExactly(TokenStyle.KEYWORD, "SYSTEM")
    }

    @Test
    fun `attribute types are types only where one belongs`() {
        dtd.assertExactly(TokenStyle.TYPE, "ID", occurrence = 1, wholeWord = true)
        dtd.assertExactly(TokenStyle.TYPE, "CDATA", wholeWord = true)
        dtd.assertPlain("ID", occurrence = 0, wholeWord = true)
    }

    @Test
    fun `entity references outside quotes are numbers`() {
        dtd.assertExactly(TokenStyle.NUMBER, "%common;")
    }

    @Test
    fun `quoted values are strings, also in entities, notations and external ids`() {
        dtd.assertExactly(TokenStyle.STRING, "\"common.ent\"")
        dtd.assertExactly(TokenStyle.STRING, "\"image/gif\"")
        dtd.assertExactly(TokenStyle.STRING, "\"a\"")
        dtd.assertExactly(TokenStyle.STRING, "\"&#169;\"")
        val single = Highlighted("<!ENTITY a 'b c'>\n<!ENTITY d PUBLIC \"-//x\" 'y'>\n", RSyntaxHighlighter.dtd)
        single.assertExactly(TokenStyle.STRING, "'b c'")
        single.assertExactly(TokenStyle.STRING, "\"-//x\"")
        single.assertExactly(TokenStyle.STRING, "'y'")
    }

    @Test
    fun `an unclosed processing instruction runs to the line end`() {
        Highlighted("<?xml version\n<!ELEMENT a ANY>\n", RSyntaxHighlighter.dtd)
            .assertExactly(TokenStyle.ANNOTATION, "<?xml version")
    }

    @Test
    fun `spans are well-formed`() = dtd.assertWellFormed()
}
