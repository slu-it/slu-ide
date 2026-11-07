package dev.sluide.languageservers

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

/** LSP positions in UTF-16 code units over texts with `\n` line ends. */
class TextPositionsTest {
    // "a😀b\nxy\n": the emoji is a surrogate pair, two code units, so "b" is at character 3.
    private val text = "a😀b\nxy\n"

    @ParameterizedTest(name = "offset {0} is {1}:{2}")
    @CsvSource(
        "0, 0, 0",
        "1, 0, 1",
        "3, 0, 3",
        "4, 0, 4",
        "5, 1, 0",
        "7, 1, 2",
        "8, 2, 0",
        "-3, 0, 0",
        "99, 2, 0",
    )
    fun `positionOf counts lines and UTF-16 characters and clamps`(
        offset: Int,
        line: Int,
        character: Int,
    ) {
        assertEquals(Position(line, character), TextPositions.positionOf(text, offset))
    }

    @ParameterizedTest(name = "{0}:{1} is offset {2}")
    @CsvSource(
        "0, 0, 0",
        "0, 3, 3",
        "1, 1, 6",
        "1, 9, 7",
        "2, 0, 8",
        "2, 5, 8",
        "7, 0, 8",
        "-1, 0, 0",
        "0, -2, 0",
    )
    fun `offsetOf is the inverse and clamps lines and characters`(
        line: Int,
        character: Int,
        offset: Int,
    ) {
        assertEquals(offset, TextPositions.offsetOf(text, Position(line, character)))
    }

    @ParameterizedTest(name = "[{0}, {1}) is {2}:{3}-{4}:{5}")
    @CsvSource(
        "1, 3, 0, 1, 0, 3",
        "3, 6, 0, 3, 1, 1",
        "0, 8, 0, 0, 2, 0",
        "6, 6, 1, 1, 1, 1",
    )
    fun `rangeOf spans lines`(
        start: Int,
        end: Int,
        startLine: Int,
        startCharacter: Int,
        endLine: Int,
        endCharacter: Int,
    ) {
        assertEquals(
            Range(Position(startLine, startCharacter), Position(endLine, endCharacter)),
            TextPositions.rangeOf(text, start, end),
        )
    }
}
