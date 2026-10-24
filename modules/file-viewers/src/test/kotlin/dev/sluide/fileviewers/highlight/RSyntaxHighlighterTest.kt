package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import org.fife.ui.rsyntaxtextarea.SyntaxConstants
import org.fife.ui.rsyntaxtextarea.TokenTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

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

    @Test
    fun `fence names map to their highlighters`() {
        val expected =
            mapOf(
                RSyntaxHighlighter.groovy to listOf("groovy", "gradle"),
                RSyntaxHighlighter.sql to listOf("sql"),
                RSyntaxHighlighter.shell to listOf("bash", "sh", "shell", "zsh"),
                RSyntaxHighlighter.powerShell to listOf("powershell", "ps1", "pwsh"),
                RSyntaxHighlighter.dockerfile to listOf("dockerfile", "docker"),
                RSyntaxHighlighter.makefile to listOf("makefile", "make"),
                RSyntaxHighlighter.ini to listOf("ini", "cfg", "conf", "editorconfig", "gitconfig"),
                RSyntaxHighlighter.ignoreFile to listOf("gitignore"),
                RSyntaxHighlighter.sshConfig to listOf("ssh-config"),
                RSyntaxHighlighter.caddyfile to listOf("caddyfile"),
                RSyntaxHighlighter.env to listOf("dotenv", "env"),
                RSyntaxHighlighter.mermaid to listOf("mermaid", "mmd"),
                KqlHighlighter to listOf("kql", "kusto", "csl"),
                TomlHighlighter to listOf("toml"),
                BicepHighlighter to listOf("bicep"),
            )
        for ((highlighter, names) in expected) {
            for (name in names) {
                assertSame(highlighter, RSyntaxHighlighter.forLanguage(name), "fence name $name")
            }
        }
    }
}
