package dev.sluide.ide.compare

import dev.sluide.fileviewers.LineChange
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitHunk
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileComparisonTest {
    private val a = Path.of("/p/x/a.txt")
    private val b = Path.of("/p/y/b.txt")
    private val comparison =
        FileComparison(a, b, "left", "right", listOf(LineChange(1..2, 1..0), LineChange(4..4, 3..5)))

    @Test
    fun `swapping trades paths, texts and the ranges of each change`() {
        val swapped = comparison.swapped()

        assertEquals(
            FileComparison(b, a, "right", "left", listOf(LineChange(1..0, 1..2), LineChange(3..5, 4..4))),
            swapped,
        )
        assertEquals(comparison, swapped.swapped())
    }

    @Test
    fun `keeps the sides of the comparison shown, whichever order it is compared in`() {
        assertEquals(comparison, comparison.inSidesOf(comparison))
        assertEquals(comparison.swapped(), comparison.inSidesOf(comparison.swapped()))
        assertEquals(comparison, comparison.swapped().inSidesOf(comparison))
        assertEquals(comparison.swapped(), comparison.swapped().inSidesOf(comparison.swapped()))
    }

    @Test
    fun `is titled with both names, and with the folders if the names are equal`() {
        assertEquals("a.txt ↔ b.txt", comparison.title())
        assertEquals("a.txt (x ↔ y)", FileComparison(a, Path.of("/p/y/a.txt"), "", "", emptyList()).title())
    }

    @Test
    fun `has the absolute paths in its tooltip`() {
        assertEquals("Comparing /p/x/a.txt and /p/y/b.txt", comparison.tooltip())
    }

    @Test
    fun `shows its pair in either order only`() {
        assertTrue(comparison.shows(a, b))
        assertTrue(comparison.shows(b, a))
        assertFalse(comparison.shows(a, a))
        assertFalse(comparison.shows(a, Path.of("/p/z.txt")))
    }

    @Test
    fun `is built from the hunks git found`() {
        val text = GitFileDiff.Text("l", "r", listOf(GitHunk(2..3, 2..2)))

        assertEquals(FileComparison(a, b, "l", "r", listOf(LineChange(2..3, 2..2))), FileComparison.of(a, b, text))
    }

    @Test
    fun `passes the column titles to the text diff`() {
        val diff = comparison.toTextDiff("L", "R")

        assertEquals("left", diff.original)
        assertEquals(comparison.changes, diff.changes)
        assertEquals("L", diff.originalTitle)
        assertEquals("R", diff.currentTitle)
    }

    @ParameterizedTest
    @CsvSource(
        "/p/src/a.txt, /p, false, src/a.txt",
        "/p/src/a.txt, /p, true, src/a.txt (saved)",
        "/q/a.txt, /p, false, /q/a.txt",
        "/q/a.txt, /p, true, /q/a.txt (saved)",
        "/p, /p, false, /p",
        "/pp/a.txt, /p, false, /pp/a.txt",
    )
    fun `titles a column relative to the project folder where it is below it`(
        file: String,
        project: String,
        unsaved: Boolean,
        expected: String,
    ) {
        assertEquals(expected, columnTitle(Path.of(file), Path.of(project), unsaved))
    }

    @Test
    fun `titles a column with the absolute path without a project folder`() {
        assertEquals("/p/a.txt", columnTitle(Path.of("/p/a.txt"), null, false))
    }

    @Test
    fun `explains why a comparison is impossible`() {
        assertNull(comparisonMessage(a, b, GitFileDiff.Text("", "", emptyList())))
        assertEquals(
            "a.txt and b.txt cannot be compared: one of them is a binary file.",
            comparisonMessage(a, b, GitFileDiff.Binary),
        )
        assertEquals(
            "a.txt and b.txt cannot be compared: one of them is larger than 5 MB.",
            comparisonMessage(a, b, GitFileDiff.TooLarge),
        )
        assertEquals("a.txt and b.txt cannot be compared.", comparisonMessage(a, b, null))
    }
}
