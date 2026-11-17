package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.fileviewers.PreviewPlace
import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.revealAround
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The place the preview under a list of places reveals and marks: what is revealed and which name is marked. */
class TargetPreviewTest {
    private val file = Path.of("a.py")
    private val text = "import os\n\ndef run(x):\n    return run + x\n"

    /** The start of `run` in line 3 (column 11). */
    private val runStart = text.indexOf("run +")

    private fun place(
        line: Int,
        column: Int,
        text: String = this.text,
    ) = targetPlace(text, SourcePosition(file, line, column))

    private fun mark(
        start: Int,
        end: Int,
    ) = StyledRange(start, end, "ci-preview-target")

    @Test
    fun `the name at its start column is marked and its line revealed with the lines around`() {
        val place = assertNotNull(place(3, 11))
        val reveal = assertNotNull(revealAround(text, 3))
        assertEquals(PreviewPlace(listOf(mark(runStart, runStart + 3)), reveal.first, reveal.last + 1), place)
    }

    @Test
    fun `the column right after the name marks that name`() {
        assertEquals(listOf(mark(runStart, runStart + 3)), place(3, 14)?.marks)
    }

    @Test
    fun `a column inside the name marks all of it`() {
        assertEquals(listOf(mark(runStart, runStart + 3)), place(3, 12)?.marks)
    }

    @Test
    fun `a column between words has no mark but the line is still revealed`() {
        val place = assertNotNull(place(3, 16))
        assertEquals(emptyList(), place.marks)
        assertEquals(assertNotNull(revealAround(text, 3)).first, place.revealStart)
    }

    @Test
    fun `a line past the end has no place`() {
        // The text ends with a line break: line 4 is the empty last line.
        assertNull(place(5, 0))
        assertNull(place(99, 0))
    }

    @Test
    fun `columns count UTF-16 units, so a character outside the BMP before the name counts twice`() {
        val emoji = "x = \"😀\" + run\n"
        val start = emoji.indexOf("run")
        // The emoji is two units: `run` starts at column 11 of the line (offset equals column on line 0).
        assertEquals(11, start)
        assertEquals(listOf(mark(start, start + 3)), place(0, 11, emoji)?.marks)
        assertEquals(emptyList(), place(0, 9, emoji)?.marks, "the plus before the name")
    }
}
