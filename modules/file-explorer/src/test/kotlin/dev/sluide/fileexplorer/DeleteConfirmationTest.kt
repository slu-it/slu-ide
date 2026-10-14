package dev.sluide.fileexplorer

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DeleteConfirmationTest {
    private val permanent = "It is deleted permanently, not moved to the trash. This cannot be undone."

    @Test
    fun `names the entry and says that deleting is permanent`() {
        assertEquals(
            DeleteQuestion("Delete the file “a.txt”?", permanent),
            deleteQuestion(Path.of("/p/a.txt"), EntryKind.FILE, 0),
        )
        assertEquals(
            DeleteQuestion("Delete the folder “src” and everything in it?", permanent),
            deleteQuestion(Path.of("/p/src"), EntryKind.FOLDER, 0),
        )
        assertEquals(
            DeleteQuestion("Delete the link “out”?", permanent),
            deleteQuestion(Path.of("/p/out"), EntryKind.LINK, 0),
        )
    }

    @Test
    fun `counts the files with unsaved changes that will be lost`() {
        assertEquals(
            "1 file with unsaved changes will be lost.\n$permanent",
            deleteQuestion(Path.of("/p/a.txt"), EntryKind.FILE, 1).details,
        )
        assertEquals(
            "3 files with unsaved changes will be lost.\n$permanent",
            deleteQuestion(Path.of("/p/src"), EntryKind.FOLDER, 3).details,
        )
    }
}
