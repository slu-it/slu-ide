package dev.sluide.fileexplorer.actions

import kotlin.test.Test
import kotlin.test.assertEquals

class DeleteConfirmationTest {
    private val permanent = "It is deleted permanently, not moved to the trash. This cannot be undone."

    @Test
    fun `names the entry and says that deleting is permanent`() {
        assertEquals(
            DeleteQuestion("Delete the file “a.txt”?", permanent),
            deleteQuestion("a.txt", EntryKind.FILE, 0),
        )
        assertEquals(
            DeleteQuestion("Delete the folder “src” and everything in it?", permanent),
            deleteQuestion("src", EntryKind.FOLDER, 0),
        )
        assertEquals(
            DeleteQuestion("Delete the link “out”?", permanent),
            deleteQuestion("out", EntryKind.LINK, 0),
        )
    }

    @Test
    fun `counts the files with unsaved changes that will be lost`() {
        assertEquals(
            "1 file with unsaved changes will be lost.\n$permanent",
            deleteQuestion("a.txt", EntryKind.FILE, 1).details,
        )
        assertEquals(
            "3 files with unsaved changes will be lost.\n$permanent",
            deleteQuestion("src", EntryKind.FOLDER, 3).details,
        )
    }

    @Test
    fun `asks once for several entries, naming them`() {
        val question =
            deleteQuestion(
                listOf(
                    "a.txt" to EntryKind.FILE,
                    "src" to EntryKind.FOLDER,
                    "o" to EntryKind.LINK,
                ),
                0,
            )

        assertEquals("Delete 3 entries?", question.header)
        assertEquals(
            "“a.txt”\n“src” and everything in it\n“o”\nThey are deleted permanently, not moved to the trash. " +
                "This cannot be undone.",
            question.details,
        )
    }

    @Test
    fun `names at most ten entries, then counts the rest, and sums the unsaved files`() {
        val entries = (1..13).map { "f$it" to EntryKind.FILE }

        val details = deleteQuestion(entries, 4).details.lines()

        assertEquals((1..10).map { "“f$it”" } + "and 3 more", details.take(11))
        assertEquals("4 files with unsaved changes will be lost.", details[11])
        assertEquals(13, deleteQuestion(entries, 0).header.filter(Char::isDigit).toInt())
    }
}
