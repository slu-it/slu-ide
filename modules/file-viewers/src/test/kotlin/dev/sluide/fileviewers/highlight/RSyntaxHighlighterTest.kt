package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.TokenTypes
import kotlin.test.Test
import kotlin.test.assertEquals

/** Corrections of [RSyntaxHighlighter] that no configured language covers on its own. */
class RSyntaxHighlighterTest {
    @Test
    fun `a null token style leaves that token type unstyled`() {
        val java =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_JAVA,
                tokenStyles = mapOf(TokenTypes.RESERVED_WORD to null),
            )
        val code = Highlighted("class A { int n = 1; }", java)

        assertEquals(emptySet(), code.stylesOf("class"))
        assertEquals(setOf(TokenStyle.TYPE), code.stylesOf("int"))
        assertEquals(setOf(TokenStyle.NUMBER), code.stylesOf("1"))
    }
}
