package dev.sluide.editorarea

import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SaveErrorsAndChoicesTest {
    private val file = "/project/a.txt"

    @Test
    fun `a missing folder and denied access get their own sentences`() {
        assertEquals("Its folder does not exist.", saveFailureReason(NoSuchFileException(file)))
        assertEquals("Access denied.", saveFailureReason(AccessDeniedException(file)))
    }

    @Test
    fun `other file system errors give their reason, or the exception`() {
        assertEquals(
            "Read-only file system",
            saveFailureReason(FileSystemException(file, null, "Read-only file system")),
        )
        val noReason = FileSystemException(file)
        assertEquals(noReason.toString(), saveFailureReason(noReason))
    }

    @Test
    fun `plain IO errors give their message, or the exception`() {
        assertEquals("Disk full", saveFailureReason(IOException("Disk full")))
        val noMessage = IOException()
        assertEquals(noMessage.toString(), saveFailureReason(noMessage))
    }

    @Test
    fun `the answer of the save dialog is Save, Don't Save, or cancel for everything else`() {
        assertEquals(SaveChoice.SAVE, saveChoiceOf(SAVE_BUTTON))
        assertEquals(SaveChoice.DISCARD, saveChoiceOf(DISCARD_BUTTON))
        assertEquals(SaveChoice.CANCEL, saveChoiceOf(ButtonType.CANCEL))
        assertEquals(SaveChoice.CANCEL, saveChoiceOf(ButtonType.CLOSE), "dismissed")
    }

    @Test
    fun `the answer of the git change dialog is Save, any Anyway button, or cancel`() {
        assertEquals(SaveChoice.SAVE, gitChangeChoiceOf(SAVE_BUTTON))
        assertEquals(SaveChoice.DISCARD, gitChangeChoiceOf(anywayButton("Check Out")))
        assertEquals(SaveChoice.CANCEL, gitChangeChoiceOf(ButtonType.CANCEL))
        assertEquals(SaveChoice.CANCEL, gitChangeChoiceOf(ButtonType("Other", ButtonBar.ButtonData.OTHER)))
    }

    @Test
    fun `the close dialog is titled for one tab or several`() {
        assertEquals("Close Tab" to "Close this tab anyway?", closeTabsTexts(1))
        assertEquals("Close Tabs" to "Close these tabs anyway?", closeTabsTexts(2))
    }

    @Test
    fun `questions show paths inside the project relative to it, others in full`() {
        val folder = Path.of("/p/app")
        assertEquals("a.txt", shownPath(Path.of("/p/app/a.txt"), folder))
        assertEquals("src/b.txt", shownPath(Path.of("/p/app/src/b.txt"), folder))
        assertEquals("/elsewhere/c.txt", shownPath(Path.of("/elsewhere/c.txt"), folder))
        assertEquals("/p/app2/d.txt", shownPath(Path.of("/p/app2/d.txt"), folder), "a sibling sharing the prefix")
        assertEquals("/p/app/a.txt", shownPath(Path.of("/p/app/a.txt"), null))
        assertEquals("a.txt", shownPath(Path.of("/p/app/a.txt"), Path.of("/p/x/../app")), "normalized")
    }
}
