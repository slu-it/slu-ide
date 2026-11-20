package dev.sluide.jshell.agent

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PositionsTest {
    @Test
    fun `a diagnostic's positions count from the piece's start`() {
        // compile-error.jsonl, lines 2 to 4: `int y = "s";` after `// setup\n`, the error on `"s"`
        val piece = Piece(9, 9, 21, "int y = \"s\";")
        assertEquals(17 to 20, diagnosticRange(piece, 8, 11))
    }

    @ParameterizedTest
    @CsvSource(
        // past the piece: the ';' JShell added
        "14, 15, 14, 14",
        // no position at all
        "-1, -1, 0, 0",
        // an end before the start
        "5, 2, 5, 5",
        "3, 9, 3, 9",
    )
    fun `diagnostic positions are clamped into the piece`(
        startPosition: Long,
        endPosition: Long,
        start: Int,
        end: Int,
    ) {
        val piece = Piece(0, 0, 14, "String s = \"x\";")
        assertEquals(start to end, diagnosticRange(piece, startPosition, endPosition))
    }

    @Test
    fun `a piece further down the document clamps to its own start`() {
        assertEquals(40 to 40, diagnosticRange(Piece(40, 42, 50, "x"), -1, -1))
    }

    @Test
    fun `a frame of a session snippet maps to the snippet's document line`() {
        // runtime-exception.jsonl, line 6: f declared on line 0 throws on its line 2, the call on line 5
        val lines = mapOf("1" to 0, "2" to 5)
        assertEquals(1, frameLine("#1", 2, lines))
        assertEquals(5, frameLine("#2", 1, lines))
    }

    @ParameterizedTest
    @CsvSource("'#9', 1", "Foo.java, 3", "'1', 1", "'', 1")
    fun `frames outside the session's snippets have no document line`(
        fileName: String,
        lineNumber: Int,
    ) {
        assertNull(frameLine(fileName, lineNumber, mapOf("1" to 4)))
    }

    @Test
    fun `a frame without a file has no document line`() {
        assertNull(frameLine(null, 1, mapOf("1" to 4)))
    }
}
