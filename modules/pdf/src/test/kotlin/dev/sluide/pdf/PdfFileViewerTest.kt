package dev.sluide.pdf

import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfFileViewerTest {
    @TempDir
    lateinit var dir: Path

    private var setups = 0
    private val viewer by lazy {
        PdfFileViewer({ Executors.newSingleThreadExecutor() }, { Executors.newSingleThreadExecutor() }, { setups++ })
    }

    private fun file(name: String): Path = Files.write(dir.resolve(name), ByteArray(16))

    @ParameterizedTest
    @ValueSource(strings = ["a.pdf", "A.PDF", "b.Pdf"])
    fun `claims pdf files in any case`(name: String) {
        assertTrue(viewer.canOpen(file(name)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["a.pdf.txt", "a.epub", "pdf", "a.ps"])
    fun `leaves other files alone`(name: String) {
        assertFalse(viewer.canOpen(file(name)))
    }

    @Test
    fun `refuses a folder and a missing file`() {
        assertFalse(viewer.canOpen(Files.createDirectory(dir.resolve("folder.pdf"))))
        assertFalse(viewer.canOpen(dir.resolve("missing.pdf")))
    }

    @Test
    fun `refuses a file it cannot read`() {
        val file = file("locked.pdf")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"))
        // Root reads every file.
        assumeFalse(Files.isReadable(file))
        assertFalse(viewer.canOpen(file))
    }

    @Test
    fun `prepares PDFBox once, when it is created`() {
        viewer.fileExtensions
        assertEquals(1, setups)
    }
}
