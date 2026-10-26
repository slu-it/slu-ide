package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class VisualBasicHighlightingTest {
    private val q = "\"\"\""

    private val vb =
        Highlighted(
            """
            |Imports System
            |' comment
            |Public Class Foo
            |    Private Async Function Run() As Task
            |        Dim x As Integer = 42
            |        Dim s As String = "he said ""hi$q
            |        If x > 3 Then Await Task.Delay(1)
            |        dim y as string
            |        REM old comment
            |        REM don't panic
            |        REM say "hi
            |        Dim t = 1
            |        x = 1 : rem after a colon
            |        Console.WriteLine("REM is no comment here")
            |    End Function
            |End Class
            |
            """.trimMargin(),
            RSyntaxHighlighter.visualBasic,
        )

    @Test
    fun `keywords in any case`() {
        for (word in listOf("Imports", "Public", "Class", "Dim", "As", "If", "Then", "End", "dim", "as")) {
            vb.assertExactly(TokenStyle.KEYWORD, word, wholeWord = true)
        }
        vb.assertExactly(TokenStyle.KEYWORD, "Async")
        vb.assertExactly(TokenStyle.KEYWORD, "Await")
        vb.assertExactly(TokenStyle.TYPE, "Integer")
        vb.assertExactly(TokenStyle.TYPE, "string", wholeWord = true)
    }

    @Test
    fun `comments, strings and numbers`() {
        vb.assertExactly(TokenStyle.COMMENT, "' comment")
        vb.assertExactly(TokenStyle.NUMBER, "42")
        assertEquals(setOf(TokenStyle.STRING), vb.stylesOf("\"he said \"\"hi\"\"\""))
    }

    @Test
    fun `REM starts a comment at the start of a statement`() {
        vb.assertExactly(TokenStyle.COMMENT, "REM old comment")
        vb.assertExactly(TokenStyle.COMMENT, "rem after a colon")
        vb.assertExactly(TokenStyle.COMMENT, "REM don't panic")
        vb.assertExactly(TokenStyle.COMMENT, "REM say \"hi")
        vb.assertExactly(TokenStyle.KEYWORD, "Dim", occurrence = 2)
        assertEquals(setOf(TokenStyle.STRING), vb.stylesOf("\"REM is no comment here\""))
    }
}
