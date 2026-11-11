package dev.sluide.ebooks

import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpubFileViewerTest {
    @TempDir
    lateinit var dir: Path

    private val viewer by lazy {
        EpubFileViewer(ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList()))
    }

    private fun file(name: String): Path = Files.write(dir.resolve(name), ByteArray(16))

    @ParameterizedTest
    @ValueSource(strings = ["a.epub", "A.EPUB", "b.Epub"])
    fun `claims epub files in any case`(name: String) {
        assertTrue(viewer.canOpen(file(name)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["a.epub.txt", "a.mobi", "epub", "a.zip"])
    fun `leaves other files alone`(name: String) {
        assertFalse(viewer.canOpen(file(name)))
    }

    @Test
    fun `refuses a directory and a missing file`() {
        assertFalse(viewer.canOpen(Files.createDirectory(dir.resolve("folder.epub"))))
        assertFalse(viewer.canOpen(dir.resolve("missing.epub")))
    }
}
