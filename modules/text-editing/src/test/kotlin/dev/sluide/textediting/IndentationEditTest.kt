package dev.sluide.textediting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IndentationEditTest {
    private val spaces = IndentStyle(useTabs = false, size = 4)
    private val tabs = IndentStyle(useTabs = true, size = 4)

    private fun newline(
        marked: String,
        style: IndentStyle = spaces,
    ) = edited(marked) { text, selection -> Indentation.newline(text, selection, style) }

    private fun indent(
        marked: String,
        style: IndentStyle = spaces,
    ) = edited(marked) { text, selection -> Indentation.indent(text, selection, style) }

    private fun outdent(
        marked: String,
        style: IndentStyle = spaces,
    ) = edited(marked) { text, selection -> Indentation.outdent(text, selection, style) }

    @Test
    fun `keeps the indentation of the line on Enter`() {
        assertEquals("a\n    b\n    |\nc", newline("a\n    b|\nc"))
        assertEquals("\tb\n\t|", newline("\tb|"))
        assertEquals("x\n|", newline("x|"))
        assertEquals("\n|", newline("|"))
    }

    @Test
    fun `replaces the selection on Enter`() {
        assertEquals("  ab\n  |", newline("  ab^cd|"))
        assertEquals("  ab\n  |", newline("  ab|cd^"))
    }

    @Test
    fun `takes the indentation only up to the caret`() {
        assertEquals("  \n  |  x", newline("  |  x"))
    }

    @Test
    fun `adds a level after an opening bracket`() {
        assertEquals("  if (a) {\n      |\n  }", newline("  if (a) {|\n  }"))
        assertEquals("f(\n    |x)", newline("f(|x)"))
        assertEquals("[ \n    |", newline("[ |"))
        assertEquals("\t{\n\t\t|", newline("\t{|", tabs))
    }

    @Test
    fun `puts the closing bracket on its own line between brackets`() {
        assertEquals("{\n    |\n}", newline("{|}"))
        assertEquals("  f(\n      |\n  )", newline("  f(|)"))
        assertEquals("  x = [\n      |\n  ]", newline("  x = [^ab|]"))
        assertEquals("\t{ \n\t\t|\n\t}", newline("\t{ |}", tabs))
        assertEquals("(\n    |]", newline("(|]"))
        assertEquals("{\n    | }", newline("{| }"))
    }

    @Test
    fun `inserts spaces to the next indentation stop`() {
        assertEquals("    |x", indent("|x"))
        assertEquals("ab  |", indent("ab|"))
        assertEquals("abcd    |", indent("abcd|"))
        assertEquals("\tab  |", indent("\tab|"))
        assertEquals("ab\t|", indent("ab|", tabs))
    }

    @Test
    fun `replaces a selection within one line`() {
        assertEquals("a   |d", indent("a^bc|d"))
    }

    @Test
    fun `indents all lines of a selection over several lines`() {
        assertEquals("    a^b\n    cd\n    e|f", indent("a^b\ncd\ne|f"))
        assertEquals("    e|f\n    ab^", indent("e|f\nab^"))
        assertEquals("^\ta\n\tb\n|c", indent("^a\nb\n|c", tabs))
    }

    @Test
    fun `selects whole lines after indenting whole lines`() {
        assertEquals("^    a\n    b\n|", indent("^a\nb\n|"))
    }

    @Test
    fun `leaves empty lines alone when indenting`() {
        assertEquals("^    a\n\n    b|", indent("^a\n\nb|"))
        assertNull(indent("^\n\n|x"))
    }

    @Test
    fun `outdents the line of the caret`() {
        assertEquals("    a|", outdent("        a|"))
        assertEquals("|a", outdent("    |a"))
        assertEquals("|a", outdent("  |  a"))
        assertEquals("a|", outdent("  a|"))
        assertEquals("\ta|", outdent("\t\ta|"))
    }

    @Test
    fun `removes a tab or spaces whatever the style`() {
        assertEquals("a|", outdent("\ta|"))
        assertEquals("  a|", outdent("      a|", tabs))
        assertEquals("\ta|", outdent("  \ta|"))
    }

    @Test
    fun `outdents all touched lines`() {
        assertEquals("^a\n  b\nc\n|    d", outdent("^    a\n      b\nc\n|    d"))
        assertEquals("a^\nb|", outdent("  a^\n\tb|"))
    }

    @Test
    fun `does nothing when no touched line is indented`() {
        assertNull(outdent("a|\nb"))
        assertNull(outdent("|"))
    }

    @Test
    fun `measures columns with tabs`() {
        assertEquals(4, Indentation.column("\tx", 1, 4))
        assertEquals(8, Indentation.column("ab\tx", 3, 8))
        assertEquals(1, Indentation.column("abc\nd", 5, 4))
    }
}
