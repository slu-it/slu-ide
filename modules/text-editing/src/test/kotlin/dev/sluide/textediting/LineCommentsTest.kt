package dev.sluide.textediting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LineCommentsTest {
    private fun toggle(
        marked: String,
        syntax: CommentSyntax = CommentSyntax.SLASHES,
    ) = edited(marked) { text, selection -> LineComments.toggle(text, selection, syntax) }

    @Test
    fun `comments the line of the caret`() {
        assertEquals("// a|b\nc", toggle("a|b\nc"))
        assertEquals("    // a|", toggle("    a|"))
        assertEquals("# x = 1|", toggle("x = 1|", CommentSyntax.HASH))
    }

    @Test
    fun `comments touched lines at their smallest indentation`() {
        assertEquals("  ^// if (a) {\n  //     b()\n  // }|", toggle("  ^if (a) {\n      b()\n  }|"))
        assertEquals("^// a\n// b\n|c", toggle("^a\nb\n|c"))
    }

    @Test
    fun `comments after the leading whitespace all lines share`() {
        assertEquals("^# \tx\n#     y|", toggle("^\tx\n    y|", CommentSyntax.HASH))
        assertEquals("^  # \tx\n  # y|", toggle("^  \tx\n  y|", CommentSyntax.HASH))
    }

    @Test
    fun `skips blank lines`() {
        assertEquals("^// a\n\n  \n// b|", toggle("^a\n\n  \nb|"))
        assertNull(toggle("^\n  \n|"))
    }

    @Test
    fun `uncomments when all non-blank lines are commented`() {
        assertEquals("a|b", toggle("// a|b"))
        assertEquals("  ^a\n\n  b|", toggle("  ^// a\n\n  //b|"))
        assertEquals("        x|", toggle("    //     x|"))
        assertEquals("|", toggle("//|"))
    }

    @Test
    fun `comments all lines when only some are commented`() {
        assertEquals("^// // a\n// b|", toggle("^// a\nb|"))
    }

    @Test
    fun `wraps lines in block comments without line comments`() {
        assertEquals("^<!-- a -->\n  <!-- b| -->", toggle("^a\n  b|", CommentSyntax.XML))
        assertEquals("  /* color: red;| */", toggle("  color: red;|", CommentSyntax.CSS))
    }

    @Test
    fun `unwraps block comments`() {
        assertEquals("^a\n  b|", toggle("^<!-- a -->\n  <!--b-->|", CommentSyntax.XML))
        assertEquals("  color: red;|", toggle("  /* color: red; */|", CommentSyntax.CSS))
        assertEquals("|", toggle("/**/|", CommentSyntax.CSS))
    }

    @Test
    fun `wraps lines with several block comments again`() {
        assertEquals("<!-- <!-- a --> <!-- b -->| -->", toggle("<!-- a --> <!-- b -->|", CommentSyntax.XML))
        assertEquals("/* /* a */ x /* b */| */", toggle("/* a */ x /* b */|", CommentSyntax.CSS))
    }

    @Test
    fun `comments lines in SQL, PowerShell, INI and Mermaid`() {
        assertEquals("-- select 1|", toggle("select 1|", CommentSyntax.SQL))
        assertEquals("# Get-Item|", toggle("Get-Item|", CommentSyntax.POWERSHELL))
        assertEquals("; key = value|", toggle("key = value|", CommentSyntax.SEMICOLON))
        assertEquals("%% A --> B|", toggle("A --> B|", CommentSyntax.MERMAID))
        assertEquals("A --> B|", toggle("%% A --> B|", CommentSyntax.MERMAID))
    }

    @Test
    fun `comments lines in batch files and Handlebars`() {
        assertEquals("REM echo off|", toggle("echo off|", CommentSyntax.REM))
        assertEquals("echo off|", toggle("REM echo off|", CommentSyntax.REM))
        assertEquals("{{!-- {{name}}| --}}", toggle("{{name}}|", CommentSyntax.HANDLEBARS))
        assertEquals("{{name}}|", toggle("{{!-- {{name}} --}}|", CommentSyntax.HANDLEBARS))
    }

    @Test
    fun `comments lines in KQL`() {
        assertEquals(
            "^// print a = 1\n// print b = 2|",
            toggle("^print a = 1\nprint b = 2|", CommentSyntax.LINE_SLASHES),
        )
        assertEquals("T|", toggle("// T|", CommentSyntax.LINE_SLASHES))
    }

    @Test
    fun `does nothing for languages without comments`() {
        assertNull(toggle("a|", CommentSyntax.NONE))
    }
}
