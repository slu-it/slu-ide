package dev.sluide.ide.files

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignO
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The explorer's Open in Default App: for which entries it shows, what it asks and which URI it hands on. */
class WindowDefaultAppActionTest {
    @TempDir
    lateinit var dir: Path

    /** What happened, in order: `ask`, `save` and `open <uri>`. */
    private val events = mutableListOf<String>()
    private var dirty = false
    private var choice = UnsavedChoice.SAVE_AND_USE

    private val action =
        WindowDefaultAppAction(
            { events += "open $it" },
            { dirty },
            {
                events += "save"
                true
            },
            {
                events += "ask"
                choice
            },
        ).action

    @Test
    fun `has its label and the open-in-new icon`() {
        assertEquals("Open in Default App", action.label)
        assertEquals(MaterialDesignO.OPEN_IN_NEW, (assertNotNull(action.icon)() as FontIcon).iconCode)
    }

    @Test
    fun `applies to a regular file but not to a folder`() {
        val file = Files.createFile(dir.resolve("report.pdf"))
        assertTrue(action.appliesTo(file))
        assertFalse(action.appliesTo(Files.createDirectory(dir.resolve("docs"))))
    }

    @Test
    fun `does not apply to a path that is gone`() {
        assertFalse(action.appliesTo(dir.resolve("gone.pdf")))
    }

    @Test
    fun `does not apply to a file with the executable bit`() {
        // The executable bit is a POSIX permission; Windows has none to set.
        assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())
        val script = Files.createFile(dir.resolve("gradlew"))
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        assertFalse(action.appliesTo(script))
    }

    @Test
    fun `a clean file opens with its URI without asking`() {
        val file = dir.resolve("my report.pdf")
        action.perform(file)
        assertEquals(listOf("open ${file.toUri()}"), events)
    }

    @Test
    fun `a dirty file asks, saves, then opens`() {
        val file = dir.resolve("a.pdf")
        dirty = true
        action.perform(file)
        assertEquals(listOf("ask", "save", "open ${file.toUri()}"), events)
    }

    @Test
    fun `a dirty file opens nothing when the question is cancelled`() {
        dirty = true
        choice = UnsavedChoice.CANCEL
        action.perform(dir.resolve("a.pdf"))
        assertEquals(listOf("ask"), events)
    }
}
