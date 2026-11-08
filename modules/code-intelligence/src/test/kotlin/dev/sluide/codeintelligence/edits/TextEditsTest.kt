package dev.sluide.codeintelligence.edits

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The pure parts of applying workspace edits: LSP positions in texts with any line breaks, the check of a file's edits
 * (in the text, not overlapping), their application from the end, and line breaks of new text in the file's own.
 */
class TextEditsTest {
    private fun edit(
        startLine: Int,
        startCharacter: Int,
        endLine: Int,
        endCharacter: Int,
        text: String,
    ) = TextEdit(Range(Position(startLine, startCharacter), Position(endLine, endCharacter)), text)

    @ParameterizedTest(name = "{0} {1}:{2} -> {3}")
    @CsvSource(
        "LF, 1, 2, 6",
        "CRLF, 1, 2, 7",
        "CR, 1, 2, 6",
        // Past the end of its line: the line's end, not the next line.
        "CRLF, 0, 9, 3",
        "LF, 2, 0, 9",
        // The line after the last one is the end of the text; further out is outside.
        "LF, 3, 4, 9",
        "CRLF, 3, 0, 11",
        "LF, 4, 0, -1",
        "LF, -1, 0, -1",
    )
    fun `positions count lines over every kind of line break and clamp characters to their line`(
        breaks: String,
        line: Int,
        character: Int,
        expected: Int,
    ) {
        val newline =
            when (breaks) {
                "CRLF" -> "\r\n"
                "CR" -> "\r"
                else -> "\n"
            }
        val text = listOf("abc", "defg", "").joinToString(newline)
        val found = offsetIn(text, Position(line, character))
        assertEquals(if (expected < 0) null else expected, found)
    }

    @Test
    fun `edits are sorted by range with inserts at one offset in the order given, and applied from the end`() {
        val text = "one two three"
        val checked =
            checkEdits(
                text,
                listOf(edit(0, 8, 0, 13, "3"), edit(0, 4, 0, 4, "a"), edit(0, 4, 0, 4, "b"), edit(0, 0, 0, 3, "1")),
            )
        val valid = assertIs<CheckedEdits.Valid>(checked)
        assertEquals(listOf(0, 4, 4, 8), valid.replacements.map { it.start })
        assertEquals("1 abtwo 3", applyReplacements(text, valid.replacements))
    }

    @Test
    fun `overlapping, reversed and outside edits are refused before anything is applied`() {
        val text = "a\nb\n"
        assertEquals(
            CheckedEdits.Invalid("overlapping edits"),
            checkEdits(text, listOf(edit(0, 0, 1, 1, "x"), edit(1, 0, 1, 0, "y"))),
        )
        assertEquals(
            CheckedEdits.Invalid("an edit that ends before it starts"),
            checkEdits(text, listOf(edit(1, 0, 0, 0, "x"))),
        )
        assertEquals(CheckedEdits.Invalid("an edit outside the text"), checkEdits(text, listOf(edit(5, 0, 5, 0, "x"))))
        // Touching edits do not overlap: one replaces up to where the next inserts.
        assertIs<CheckedEdits.Valid>(checkEdits(text, listOf(edit(0, 0, 0, 1, "x"), edit(0, 1, 0, 1, "y"))))
    }

    @Test
    fun `characters outside the Basic Multilingual Plane count twice and are never split`() {
        val emoji = String(Character.toChars(0x1F600))
        val text = "a${emoji}b\nc"
        assertEquals(3, offsetIn(text, Position(0, 3)), "the emoji takes characters 1 and 2")
        assertEquals(
            CheckedEdits.Invalid("an edit that splits a character"),
            checkEdits(text, listOf(edit(0, 2, 0, 2, "x"))),
        )
        assertEquals(
            CheckedEdits.Invalid("an edit that splits a character"),
            checkEdits(text, listOf(edit(0, 0, 0, 2, "x"))),
        )
        val valid =
            assertIs<CheckedEdits.Valid>(checkEdits(text, listOf(edit(0, 1, 0, 3, "e"), edit(1, 0, 1, 1, emoji))))
        assertEquals("aeb\n$emoji", applyReplacements(text, valid.replacements))
    }

    @Test
    fun `a file with CRLF keeps it, also in the new texts`() {
        val text = "import os\r\nprint(1)\r\n"
        val ending = lineEndingOf(text)
        assertEquals("\r\n", ending)
        val valid = assertIs<CheckedEdits.Valid>(checkEdits(text, listOf(edit(1, 0, 1, 0, "import sys\n")), ending))
        assertEquals("import os\r\nimport sys\r\nprint(1)\r\n", applyReplacements(text, valid.replacements))
    }

    @Test
    fun `the line ending of a text is its first, LF without any, and new texts are converted from any`() {
        assertEquals("\n", lineEndingOf("no breaks"))
        assertEquals("\r", lineEndingOf("a\rb\r\n"))
        assertEquals("\n", lineEndingOf("a\nb\r\n"))
        assertEquals("a\r\nb\r\nc\r\n", withLineEnding("a\nb\r\nc\r", "\r\n"))
        assertEquals("a\nb\nc\n", withLineEnding("a\nb\r\nc\r", "\n"))
    }
}
