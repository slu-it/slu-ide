package dev.sluide.fileexplorer

import dev.sluide.fileexplorer.actions.systemFileManager
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FileManagerTest {
    @TempDir
    lateinit var folder: Path

    @Test
    fun `the name for messages is that of the file manager of this system`() {
        assertEquals(systemFileManager().name, fileManagerName)
        assertTrue(fileManagerName.isNotEmpty())
    }

    @Test
    fun `opening a folder starts the file manager command and reports a program that cannot start`() {
        // Only where the command is xdg-open and it is not installed: elsewhere the call would open a real window.
        val linux = System.getProperty("os.name").lowercase().contains("linux")
        val installed =
            System.getenv("PATH").orEmpty().split(File.pathSeparator).any {
                it.isNotEmpty() && Files.isExecutable(Path.of(it, "xdg-open"))
            }
        assumeTrue(linux && !installed)

        val failure = assertFailsWith<IOException> { openInFileManager(folder) }

        assertTrue("xdg-open" in failure.message.orEmpty(), failure.message)
    }
}
