package dev.sluide.codeintelligence.edits

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

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

    /** A TextEdit as lsp4j parses a server's JSON: what the JSON lacks stays null. */
    private fun parsedEdit(json: String): TextEdit =
        MessageJsonHandler(emptyMap()).gson.fromJson(json, TextEdit::class.java)

    private val start0 = "\"start\":{\"line\":0,\"character\":0}"
    private val end1 = "\"end\":{\"line\":0,\"character\":1}"

    @Test
    fun `an edit the JSON leaves incomplete is refused with its reason`() {
        assertEquals(
            CheckedEdits.Invalid("an edit without a range"),
            checkEdits("abc", listOf(parsedEdit("{\"newText\":\"x\"}"))),
        )
        assertEquals(
            CheckedEdits.Invalid("an edit outside the text"),
            checkEdits("abc", listOf(parsedEdit("{\"range\":{$end1},\"newText\":\"x\"}"))),
        )
        assertEquals(
            CheckedEdits.Invalid("an edit outside the text"),
            checkEdits("abc", listOf(parsedEdit("{\"range\":{$start0},\"newText\":\"x\"}"))),
        )
        val withoutText =
            assertIs<CheckedEdits.Valid>(checkEdits("abc", listOf(parsedEdit("{\"range\":{$start0,$end1}}"))))
        assertEquals(listOf(TextReplacement(0, 1, "")), withoutText.replacements)
    }

    @Test
    fun `a negative character is outside the text, in every line`() {
        assertNull(offsetIn("abc\ndef", Position(0, -1)))
        assertNull(offsetIn("abc\ndef", Position(1, -3)))
        assertEquals(
            CheckedEdits.Invalid("an edit outside the text"),
            checkEdits("abc", listOf(edit(0, 0, 0, -1, "x"))),
        )
    }

    @Test
    fun `a text of many lines is indexed completely`() {
        val lines = (0 until 70).map { "line$it" }
        for (breaks in listOf("\n", "\r\n")) {
            val text = lines.joinToString(breaks)
            val starts = lines.runningFold(0) { at, line -> at + line.length + breaks.length }
            for (line in listOf(0, 15, 16, 17, 31, 32, 33, 69)) {
                assertEquals(
                    starts[line] + 2,
                    offsetIn(text, Position(line, 2)),
                    "line $line with ${breaks.length} breaks",
                )
            }
            assertEquals(text.length, offsetIn(text, Position(70, 0)))
            assertNull(offsetIn(text, Position(71, 0)))
        }
    }

    @Test
    fun `only a high surrogate followed by a low one is a split, anywhere in the text`() {
        val emoji = String(Character.toChars(0x1F600))
        // At the start and at the end of the text, where the neighbour on one side does not exist.
        assertIs<CheckedEdits.Valid>(checkEdits("${emoji}a", listOf(edit(0, 0, 0, 0, "x"))))
        assertIs<CheckedEdits.Valid>(checkEdits("a$emoji", listOf(edit(0, 3, 0, 3, "x"))))
        // Between the first pair of a text, and a text that ends in a lone high surrogate.
        assertEquals(
            CheckedEdits.Invalid("an edit that splits a character"),
            checkEdits("${emoji}a", listOf(edit(0, 1, 0, 1, "x"))),
        )
        assertIs<CheckedEdits.Valid>(checkEdits("a\uD83D", listOf(edit(0, 2, 0, 2, "x"))))
        // A lone high surrogate before an ordinary character, a lone low one after an ordinary character.
        assertIs<CheckedEdits.Valid>(checkEdits("\uD83Dx", listOf(edit(0, 1, 0, 1, "y"))))
        assertIs<CheckedEdits.Valid>(checkEdits("x\uDE00", listOf(edit(0, 1, 0, 1, "y"))))
        // A split on the start only, on the end only, and on a later line.
        val text = "a${emoji}b\nc$emoji"
        val splitting = CheckedEdits.Invalid("an edit that splits a character")
        assertEquals(splitting, checkEdits(text, listOf(edit(0, 2, 0, 3, "x"))))
        assertEquals(splitting, checkEdits(text, listOf(edit(1, 2, 1, 2, "x"))))
        assertEquals(splitting, checkEdits(text, listOf(edit(1, 0, 1, 2, "x"))))
    }

    @Test
    fun `edits with one start are ordered by their end, and new text keeps its breaks without a line ending`() {
        val text = "0123456789"
        // The replacement comes first in the list, the insert at its start must still be sorted before it.
        val valid =
            assertIs<CheckedEdits.Valid>(checkEdits(text, listOf(edit(0, 4, 0, 6, "R"), edit(0, 4, 0, 4, "I\r\n"))))
        assertEquals(listOf(TextReplacement(4, 4, "I\r\n"), TextReplacement(4, 6, "R")), valid.replacements)
        assertEquals("0123I\r\nR6789", applyReplacements(text, valid.replacements))
    }

    @Test
    fun `edits given out of order are sorted by their start`() {
        val valid =
            assertIs<CheckedEdits.Valid>(checkEdits("abcdef", listOf(edit(0, 4, 0, 5, "E"), edit(0, 0, 0, 1, "A"))))
        assertEquals(listOf(0, 4), valid.replacements.map { it.start })
    }

    @Test
    fun `an insert where a replacement ends comes after it, whatever the order given`() {
        val valid =
            assertIs<CheckedEdits.Valid>(checkEdits("abcdef", listOf(edit(0, 2, 0, 2, "I"), edit(0, 0, 0, 2, "R"))))
        assertEquals(listOf(TextReplacement(0, 2, "R"), TextReplacement(2, 2, "I")), valid.replacements)
    }

    @Test
    fun `the line ending is found at the very start, at the very end and after a carriage return`() {
        assertEquals("\r\n", lineEndingOf("\r\nabc"))
        assertEquals("\n", lineEndingOf("\nabc"))
        assertEquals("\r", lineEndingOf("\rabc"))
        assertEquals("\r", lineEndingOf("abc\r"))
        assertEquals("\r\n", lineEndingOf("abc\r\n"))
    }
}
