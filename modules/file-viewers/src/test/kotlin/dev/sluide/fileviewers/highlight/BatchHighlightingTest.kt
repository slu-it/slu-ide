package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class BatchHighlightingTest {
    private val batch =
        Highlighted(
            """
            |@echo off
            |REM comment %NOTVAR%
            |rem lower
            |Rem Mixed
            |:: double colon
            |SetLocal EnableDelayedExpansion
            |set "NAME=World"
            |:main
            |  :indented
            |echo Hello %NAME% from %~dp0 arg %1 and !COUNT! "%NAME%"
            |if "%1"=="" goto :usage
            |if exist "C:\x" (
            |    ECHO found
            |) ELSE (
            |    echo missing
            |)
            |for %%f in (*.txt) do call :process "%%f"
            |goto end
            |:usage
            |timeout /t 5 & Title x
            |exit /b 1
            |for %%f in (*.txt) do echo %%~nxf %%~dpf
            |echo "%%~nxf" "%%~dpf"
            |echo "Value not defined, else fails"
            |echo "please goto :end"
            |
            """.trimMargin(),
            RSyntaxHighlighter.batch,
        )

    @Test
    fun `comments in any case, double colons too`() {
        for (comment in listOf("REM comment %NOTVAR%", "rem lower", "Rem Mixed", ":: double colon")) {
            assertEquals(setOf(TokenStyle.COMMENT), batch.stylesOf(comment), comment)
        }
    }

    @Test
    fun `commands and keywords in any case`() {
        for (keyword in listOf("echo", "ECHO", "set", "if", "exist", "for", "goto", "exit")) {
            assertEquals(setOf(TokenStyle.KEYWORD), batch.stylesOf(keyword), keyword)
        }
        for (keyword in listOf("SetLocal", "EnableDelayedExpansion", "ELSE")) {
            assertEquals(setOf(TokenStyle.KEYWORD), batch.stylesOf(keyword), keyword)
        }
        assertEquals(setOf(TokenStyle.BUILTIN), batch.stylesOf("timeout"))
        assertEquals(setOf(TokenStyle.BUILTIN), batch.stylesOf("Title"))
    }

    @Test
    fun `variables are annotations, also in quotes`() {
        for (variable in listOf("%~dp0", "%1", "!COUNT!", "%%f")) {
            batch.assertExactly(TokenStyle.ANNOTATION, variable)
        }
        batch.assertExactly(TokenStyle.ANNOTATION, "%NAME%", occurrence = 0)
        batch.assertExactly(TokenStyle.ANNOTATION, "%NAME%", occurrence = 1)
        assertEquals(setOf(TokenStyle.ANNOTATION, TokenStyle.STRING), batch.stylesOf("\"%NAME%\""))
    }

    @Test
    fun `modifiers of loop variables are part of the variable`() {
        batch.assertExactly(TokenStyle.ANNOTATION, "%%~nxf")
        batch.assertExactly(TokenStyle.ANNOTATION, "%%~dpf")
        // Inside quotes only the line rule styles them, the lexer takes the whole string.
        batch.assertExactly(TokenStyle.ANNOTATION, "%%~nxf", occurrence = 1)
        batch.assertExactly(TokenStyle.ANNOTATION, "%%~dpf", occurrence = 1)
    }

    @Test
    fun `nothing but variables is styled inside strings`() {
        batch.assertExactly(TokenStyle.STRING, "\"Value not defined, else fails\"")
        batch.assertExactly(TokenStyle.STRING, "\"please goto :end\"")
    }

    @Test
    fun `labels are types, double colons are not`() {
        batch.assertExactly(TokenStyle.TYPE, ":main")
        batch.assertExactly(TokenStyle.TYPE, ":indented")
        batch.assertExactly(TokenStyle.TYPE, ":usage", occurrence = 0)
        batch.assertExactly(TokenStyle.TYPE, ":usage", occurrence = 1)
        batch.assertExactly(TokenStyle.TYPE, ":process")
        batch.assertExactly(TokenStyle.TYPE, "end", wholeWord = true)
    }

    @Test
    fun `at sign and strings`() {
        assertEquals(setOf(TokenStyle.KEYWORD), batch.stylesOf("echo off"))
        assertEquals(setOf(TokenStyle.STRING), batch.stylesOf("\"C:\\x\""))
    }

    @Test
    fun `spans are well-formed`() = batch.assertWellFormed()
}
