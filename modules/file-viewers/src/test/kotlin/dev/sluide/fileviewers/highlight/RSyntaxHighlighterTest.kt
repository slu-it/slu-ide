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
    fun `lexer masks hide text from the lexer without moving the rest`() {
        val java =
            RSyntaxHighlighter(
                SyntaxConstants.SYNTAX_STYLE_JAVA,
                lexerMasks = listOf(Regex("""'(?=a)""")),
                lineRules = listOf(Regex("""(?<type>'a)""")),
            )
        // Unmasked, the quote starts a character literal that reaches over `class` to the next quote.
        val code = Highlighted("int 'a class 'b' + 1;", java)

        assertEquals(setOf(TokenStyle.KEYWORD), code.stylesOf("class"))
        assertEquals(setOf(TokenStyle.TYPE), code.stylesOf("'a"), "line rules still see the original text")
        assertEquals(setOf(TokenStyle.STRING), code.stylesOf("'b'"))
        assertEquals(setOf(TokenStyle.NUMBER), code.stylesOf("1"), "offsets are unchanged")
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
                RSyntaxHighlighter.c to listOf("c"),
                RSyntaxHighlighter.cpp to listOf("cpp", "c++", "cxx", "h", "hpp"),
                RSyntaxHighlighter.csharp to listOf("csharp", "cs", "c#"),
                RSyntaxHighlighter.go to listOf("go", "golang"),
                RSyntaxHighlighter.goMod to listOf("gomod", "go.mod", "go.work"),
                RSyntaxHighlighter.rust to listOf("rust", "rs"),
                RSyntaxHighlighter.d to listOf("d"),
                RSyntaxHighlighter.scala to listOf("scala"),
                RSyntaxHighlighter.dart to listOf("dart"),
                RSyntaxHighlighter.visualBasic to listOf("vb", "vbnet", "vbscript"),
                RSyntaxHighlighter.pascal to listOf("pascal", "delphi", "pas"),
                RSyntaxHighlighter.fortran to listOf("fortran", "f90"),
                RSyntaxHighlighter.assembler to listOf("asm", "nasm", "x86asm"),
                RSyntaxHighlighter.vhdl to listOf("vhdl"),
            )
        for ((highlighter, names) in expected) {
            for (name in names) {
                assertSame(highlighter, RSyntaxHighlighter.forLanguage(name), "fence name $name")
            }
        }
    }
}
