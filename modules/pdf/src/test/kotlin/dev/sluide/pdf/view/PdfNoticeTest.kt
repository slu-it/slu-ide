package dev.sluide.pdf.view

import dev.sluide.pdf.engine.PdfOpenResult
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfNoticeTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a document that cannot be shown gets a notice saying why`() {
        assertEquals(
            "Not a valid PDF: Header doesn't contain versioninfo",
            noticeText(PdfOpenResult.Invalid("Header doesn't contain versioninfo")),
        )
        assertEquals("This PDF has no pages.", noticeText(PdfOpenResult.NoPages))
        assertEquals("This PDF is too large to open.", noticeText(PdfOpenResult.TooLarge))
    }

    @Test
    fun `a document that wants a password gets no notice text of its own, the prompt asks`() {
        assertNull(noticeText(PdfOpenResult.NeedsPassword))
        assertNull(noticeText(PdfOpenResult.WrongPassword))
    }

    @Test
    fun `the file stamp tells a changed, a missing and a returning file from an unchanged one`() {
        val file = dir.resolve("a.pdf").also { it.writeText("one") }
        val stamp = FileStamp { file }
        assertFalse(stamp.update())
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5_000))
        assertTrue(stamp.update())
        assertFalse(stamp.update())
        val time = Files.getLastModifiedTime(file)
        file.writeText("longer")
        Files.setLastModifiedTime(file, time)
        assertTrue(stamp.update(), "a new size alone")
        Files.delete(file)
        assertTrue(stamp.update())
        assertFalse(stamp.update())
        file.writeText("back")
        assertTrue(stamp.update())
    }
}
