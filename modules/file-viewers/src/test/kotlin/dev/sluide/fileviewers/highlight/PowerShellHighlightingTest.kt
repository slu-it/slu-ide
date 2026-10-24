package dev.sluide.fileviewers.highlight

import dev.sluide.fileviewers.TokenStyle
import kotlin.test.Test
import kotlin.test.assertEquals

class PowerShellHighlightingTest {
    private val d = "$"

    private val powerShell =
        Highlighted(
            """
            |# greets
            |<# over
            |   two lines #>
            |function Get-Greeting {
            |    [CmdletBinding()]
            |    param([string]${d}Name = "world", [int[]]${d}Counts)
            |    if (${d}Name -eq ${d}null -and ${d}true) { return 'none' }
            |    foreach (${d}c in ${d}Counts) { Write-Host "Hello ${d}Name" ${d}env:PATH ${d}_ $d{odd name} 0x1F }
            |    [System.IO.Path]::Combine(${d}a, 'b')
            |}
            |
            """.trimMargin(),
            RSyntaxHighlighter.powerShell,
        )

    @Test
    fun `keywords, cmdlets and constants`() {
        for (keyword in listOf("function", "param", "if", "foreach", "return", "${d}null", "${d}true")) {
            assertEquals(setOf(TokenStyle.KEYWORD), powerShell.stylesOf(keyword), keyword)
        }
        assertEquals(setOf(TokenStyle.BUILTIN), powerShell.stylesOf("Write-Host"))
        assertEquals(setOf(TokenStyle.OPERATOR), powerShell.stylesOf("-eq"))
    }

    @Test
    fun `variables are annotations, outside strings`() {
        for (variable in listOf("${d}Name", "${d}Counts", "${d}c", "${d}env:PATH", "${d}_", "$d{odd name}")) {
            assertEquals(setOf(TokenStyle.ANNOTATION), powerShell.stylesOf(variable), variable)
        }
        assertEquals(setOf(TokenStyle.STRING), powerShell.stylesOf("\"Hello ${d}Name\""))
    }

    @Test
    fun `types in brackets and attributes`() {
        assertEquals(setOf(TokenStyle.TYPE), powerShell.stylesOf("string"))
        assertEquals(setOf(TokenStyle.TYPE), powerShell.stylesOf("int"))
        assertEquals(setOf(TokenStyle.TYPE), powerShell.stylesOf("System.IO.Path"))
        assertEquals(setOf(TokenStyle.ANNOTATION), powerShell.stylesOf("CmdletBinding"))
    }

    @Test
    fun `constants in any case, indexes are no types`() {
        val text = "if (${d}x -eq ${d}Null -or ${d}True) { ${d}arr[index] + ${d}h[0] + (Get-Item)[Name] }\n"
        val other = Highlighted(text, RSyntaxHighlighter.powerShell)

        assertEquals(setOf(TokenStyle.KEYWORD), other.stylesOf("${d}Null"))
        assertEquals(setOf(TokenStyle.KEYWORD), other.stylesOf("${d}True"))
        assertEquals(emptySet(), other.stylesOf("index"))
        assertEquals(emptySet(), other.stylesOf("Name"))
        assertEquals(setOf(TokenStyle.ANNOTATION), other.stylesOf("${d}arr"))
    }

    @Test
    fun `comments, strings and numbers`() {
        assertEquals(setOf(TokenStyle.COMMENT), powerShell.stylesOf("# greets"))
        assertEquals(setOf(TokenStyle.COMMENT), powerShell.stylesOf("two lines #>"))
        assertEquals(setOf(TokenStyle.STRING), powerShell.stylesOf("'none'"))
        assertEquals(setOf(TokenStyle.NUMBER), powerShell.stylesOf("0x1F"))
    }

    @Test
    fun `spans are well-formed`() = powerShell.assertWellFormed()
}
