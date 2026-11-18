package dev.sluide.encryptedfiles.ui

import dev.sluide.encryptedfiles.SluencFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

class EncryptedTextsTest {
    @ParameterizedTest
    @CsvSource("notes.md.sluenc, notes.md", "Notes.TXT.SLUENC, Notes.TXT", "notes.sluenc, notes", "notes.md, notes.md")
    fun `the inner name drops the extension in any case`(
        name: String,
        inner: String,
    ) {
        assertEquals(inner, innerName(name))
    }

    @Test
    fun `the virtual path lies beside the file`() {
        assertEquals(Path.of("/a/b/notes.json"), virtualPath(Path.of("/a/b/notes.json.sluenc")))
    }

    @ParameterizedTest
    @CsvSource("notes.png, Cannot show .png files.", "notes, Cannot show this file's content.")
    fun `without a viewer the locked pane says why`(
        inner: String,
        text: String,
    ) {
        assertEquals(text, cannotShowText(inner))
    }

    @ParameterizedTest
    @CsvSource("1, 1 encrypted file has unsaved changes.", "3, 3 encrypted files have unsaved changes.")
    fun `Lock All asks with the count`(
        count: Int,
        text: String,
    ) {
        assertEquals(text, lockAllQuestion(count))
    }

    @ParameterizedTest
    @CsvSource(
        "1, '1 encrypted file stays unlocked: its changes could not be saved.'",
        "2, '2 encrypted files stay unlocked: their changes could not be saved.'",
    )
    fun `Lock All says how many tabs stay unlocked`(
        count: Int,
        text: String,
    ) {
        assertEquals(text, stayedUnlockedText(count))
    }

    @Test
    fun `a newer version names its format, or says it is unknown`() {
        assertEquals("Written by a newer Thoreau (format 3).", failureMessage(SluencFailure.NEWER_VERSION, 3))
        assertEquals("Written by a newer Thoreau (format unknown).", failureMessage(SluencFailure.NEWER_VERSION))
    }

    @Test
    fun `the strip warns that saving replaces a file that changed`() {
        assertEquals(
            "Changed elsewhere with another password. Saving replaces it with your version.",
            changedOnDiskWarning(SluencFailure.KEY_MISMATCH),
        )
        assertEquals(
            "The file is damaged. Saving replaces it with your version.",
            changedOnDiskWarning(SluencFailure.DAMAGED),
        )
        assertEquals(
            "Written by a newer Thoreau (format 2). Saving replaces it with your version.",
            changedOnDiskWarning(SluencFailure.NEWER_VERSION, 2),
        )
    }

    @Test
    fun `the replace question names the file and why`() {
        assertEquals(
            "Replace a.sluenc? It was changed elsewhere with another password; saving writes your version under the " +
                "password you unlocked it with.",
            replaceQuestion("a.sluenc", SluencFailure.KEY_MISMATCH),
        )
        assertEquals(
            "Replace a.sluenc? The file is damaged. Saving writes your version under the password you unlocked it with.",
            replaceQuestion("a.sluenc", SluencFailure.DAMAGED),
        )
    }

    @Test
    fun `titles and questions name the file`() {
        assertEquals("a.sluenc is encrypted.", encryptedTitle("a.sluenc"))
        assertEquals("a.sluenc has unsaved changes.", unsavedQuestion("a.sluenc"))
    }

    @Test
    fun `a failed save says the reason of the file system, or of the exception`() {
        fun text(e: IOException) = saveErrorText("a", e)
        assertEquals("a could not be saved. Its folder does not exist.", text(NoSuchFileException("x")))
        assertEquals("a could not be saved. Access denied.", text(AccessDeniedException("x")))
        assertEquals("a could not be saved. Disk full", text(FileSystemException("x", null, "Disk full")))
        assertEquals(
            "a could not be saved. The file system refused it.",
            text(FileSystemException("x")),
        )
        assertEquals("a could not be saved. Odd character", text(IOException("Odd character")))
        assertEquals("a could not be saved. Unknown error.", text(IOException()))
        assertEquals(
            "The password was changed and the file saved, but showing it again failed. Disk full",
            changedButFailedText(FileSystemException("x", null, "Disk full")),
        )
    }
}
