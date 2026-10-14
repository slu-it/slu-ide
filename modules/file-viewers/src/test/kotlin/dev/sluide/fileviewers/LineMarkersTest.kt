package dev.sluide.fileviewers

import dev.sluide.fileviewers.LineMarker.ADDED
import dev.sluide.fileviewers.LineMarker.MODIFIED
import dev.sluide.fileviewers.LineMarker.REMOVED_ABOVE
import kotlin.test.Test
import kotlin.test.assertEquals

class LineMarkersTest {
    @Test
    fun `added and modified lines are marked as such`() {
        // a b c -> a B c x y
        val markers = LineMarkers.of(listOf(LineChange(1..1, 1..1), LineChange(3 until 3, 3..4)), 5)
        assertEquals(listOf(null, MODIFIED, null, ADDED, ADDED), markers)
    }

    @Test
    fun `removed lines mark the line after them`() {
        // a b c d -> a d
        assertEquals(listOf(null, REMOVED_ABOVE), LineMarkers.of(listOf(LineChange(1..2, 1 until 1)), 2))
    }

    @Test
    fun `removed lines at the end mark the last line`() {
        // a b c -> a
        assertEquals(listOf(REMOVED_ABOVE), LineMarkers.of(listOf(LineChange(1..2, 1 until 1)), 1))
    }

    @Test
    fun `a removal does not hide the marker of a changed line after it`() {
        val changes = listOf(LineChange(1..1, 1 until 1), LineChange(3 until 3, 1..1))
        assertEquals(listOf(null, ADDED, null), LineMarkers.of(changes, 3))
    }

    @Test
    fun `unchanged files and empty files have no markers`() {
        assertEquals(listOf(null, null), LineMarkers.of(emptyList(), 2))
        assertEquals(emptyList(), LineMarkers.of(listOf(LineChange(0..1, 0 until 0)), 0))
    }

    @Test
    fun `changes beyond the lines are cut off`() {
        assertEquals(listOf(null, ADDED), LineMarkers.of(listOf(LineChange(1 until 1, 1..5)), 2))
    }

    @Test
    fun `edited lines become modified and lines below move along`() {
        val markers = listOf(null, ADDED, null, REMOVED_ABOVE)
        // Line 2 replaced by two lines.
        assertEquals(listOf(null, ADDED, MODIFIED, ADDED, REMOVED_ABOVE), LineMarkers.afterEdit(markers, 2, 1, 2))
    }

    @Test
    fun `edited added lines stay added`() {
        assertEquals(listOf(null, ADDED), LineMarkers.afterEdit(listOf(null, ADDED), 1, 1, 1))
    }

    @Test
    fun `inserted lines are added`() {
        assertEquals(listOf(null, ADDED, ADDED, MODIFIED), LineMarkers.afterEdit(listOf(null, MODIFIED), 1, 0, 2))
        assertEquals(listOf(null, ADDED), LineMarkers.afterEdit(listOf(null), 1, 0, 1))
    }

    @Test
    fun `removed lines mark the line after them, or the last line`() {
        assertEquals(listOf(null, REMOVED_ABOVE), LineMarkers.afterEdit(listOf(null, null, null), 1, 1, 0))
        assertEquals(listOf(null, MODIFIED), LineMarkers.afterEdit(listOf(null, null, MODIFIED), 1, 1, 0))
        assertEquals(listOf(REMOVED_ABOVE), LineMarkers.afterEdit(listOf(null, null), 1, 1, 0))
    }

    @Test
    fun `typing within a line edits that line`() {
        assertEquals(LineEdit(3, 1, 1), LineEdit.of(3, startsLine = false, endsLine = false, "", "x"))
        assertEquals(LineEdit(3, 1, 1), LineEdit.of(3, startsLine = true, endsLine = true, "", "x"))
        assertEquals(LineEdit(3, 1, 1), LineEdit.of(3, startsLine = true, endsLine = false, "a", ""))
    }

    @Test
    fun `a line break within a line splits it`() {
        assertEquals(LineEdit(3, 1, 2), LineEdit.of(3, startsLine = false, endsLine = false, "", "\n"))
        // Joining with the next line, which is not empty.
        assertEquals(LineEdit(3, 2, 1), LineEdit.of(3, startsLine = false, endsLine = false, "\n", ""))
    }

    @Test
    fun `whole lines leave the line they are next to alone`() {
        // Enter at the end of line 3, also with an indent.
        assertEquals(LineEdit(4, 0, 1), LineEdit.of(3, startsLine = false, endsLine = true, "", "\n"))
        assertEquals(LineEdit(4, 0, 1), LineEdit.of(3, startsLine = false, endsLine = true, "", "\n    "))
        // Enter at the start of line 3.
        assertEquals(LineEdit(3, 0, 1), LineEdit.of(3, startsLine = true, endsLine = false, "", "\n"))
        // Pasting two lines at the start of line 3; deleting line 3 with its line break.
        assertEquals(LineEdit(3, 0, 2), LineEdit.of(3, startsLine = true, endsLine = false, "", "a\nb\n"))
        assertEquals(LineEdit(3, 1, 0), LineEdit.of(3, startsLine = true, endsLine = false, "abc\n", ""))
        // Deleting line 4 from the end of line 3.
        assertEquals(LineEdit(4, 1, 0), LineEdit.of(3, startsLine = false, endsLine = true, "\nabc", ""))
    }

    @Test
    fun `change markers follow edits and are exact again at the diff's text`() {
        // HEAD: a b; saved: a B (modified).
        val markers = ChangeMarkers("a\nB")
        markers.show(TextDiff("a\nb", listOf(LineChange(1..1, 1..1))), "a\nB", "a\nB")
        assertEquals(listOf(null, MODIFIED), markers.markers)

        markers.edited(LineEdit(0, 1, 1))
        assertEquals(listOf(MODIFIED, MODIFIED), markers.markers)

        markers.resetIfUnedited("x\nB")
        assertEquals(listOf(MODIFIED, MODIFIED), markers.markers, "still edited")
        markers.resetIfUnedited("a\nB")
        assertEquals(listOf(null, MODIFIED), markers.markers)
    }

    @Test
    fun `a diff shown while edited gets the edits since the saved text again`() {
        val markers = ChangeMarkers("a\nb\nc")
        // An unsaved line x after a.
        markers.edited(LineEdit(1, 0, 1))
        markers.show(TextDiff("a\nc", listOf(LineChange(1 until 1, 1..1))), "a\nb\nc", "a\nx\nb\nc")
        assertEquals(listOf(null, ADDED, ADDED, null), markers.markers)
        markers.show(null, "a\nb\nc", "a\nx\nb\nc")
        assertEquals(listOf(null, ADDED, null, null), markers.markers)
    }

    /** A text of [lines] lines `line 1`, `line 2`, … */
    private fun lines(lines: Int) = (1..lines).joinToString("\n") { "line $it" }

    @Test
    fun `lines edited apart stay apart when the same diff is shown again and after saving`() {
        val saved = lines(300)
        val markers = ChangeMarkers(saved)
        val diff = TextDiff(saved.replace("line 1\n", "line 0\n"), listOf(LineChange(0..0, 0..0)))
        markers.show(diff, saved, saved)
        markers.edited(LineEdit(1, 1, 1))
        markers.edited(LineEdit(299, 1, 1))
        val edited = saved.replace("line 2\n", "line two\n").replace("line 300", "line 300!")
        val expected = listOf(MODIFIED, MODIFIED) + List(297) { null } + MODIFIED
        assertEquals(expected, markers.markers)

        markers.show(diff.copy(), saved, edited)
        assertEquals(expected, markers.markers, "same diff")

        markers.saved(edited, edited)
        markers.resetIfUnedited(edited)
        assertEquals(expected, markers.markers, "saved")
    }

    @Test
    fun `a new diff of the same saved text gets the edits again`() {
        val saved = lines(20)
        val markers = ChangeMarkers(saved)
        markers.edited(LineEdit(1, 1, 1))
        markers.edited(LineEdit(15, 1, 1))
        // A commit while edited: now the file differs from HEAD in line 10.
        markers.show(TextDiff(saved, listOf(LineChange(9..9, 9..9))), saved, "")
        assertEquals(
            setOf(1, 9, 15),
            markers.markers.indices
                .filter { markers.markers[it] == MODIFIED }
                .toSet(),
        )
    }

    @Test
    fun `without the edits a diff of the saved text is moved along with the replacement`() {
        val markers = ChangeMarkers("a\nb\nc")
        // E.g. the user kept the unsaved text a b c over the file's new text a c.
        markers.saved("a\nc", "a\nb\nc")
        markers.show(null, "a\nc", "a\nb\nc")
        assertEquals(listOf(null, ADDED, null), markers.markers)
    }

    @Test
    fun `an edit is found in the text before it`() {
        assertEquals(LineEdit(1, 1, 1), LineEdit.at("a\nbc\nd", 3, "c", "x"))
        assertEquals(LineEdit(2, 0, 1), LineEdit.at("a\nbc\nd", 4, "", "\nnew"))
        assertEquals(LineEdit(1, 1, 0), LineEdit.between("a\nb\nc", "a\nc"))
        assertEquals(null, LineEdit.between("a", "a"))
    }
}
