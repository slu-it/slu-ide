package dev.sluide.encryptedfiles.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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
}
