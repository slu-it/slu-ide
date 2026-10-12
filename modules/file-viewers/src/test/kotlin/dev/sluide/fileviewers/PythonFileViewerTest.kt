package dev.sluide.fileviewers

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PythonFileViewerTest {
    @TempDir
    lateinit var dir: Path

    private val viewer = PythonFileViewer()

    private fun file(name: String) = dir.resolve(name).apply { writeText("print('hi')\n") }

    @Test
    fun `opens Python sources, also with upper-case extension`() {
        for (name in listOf("main.py", "gui.pyw", "types.pyi", "LEGACY.PY")) {
            assertTrue(viewer.canOpen(file(name)), name)
        }
    }

    @Test
    fun `does not open other files, binary py files or missing files`() {
        assertFalse(viewer.canOpen(file("notes.txt")))
        assertFalse(viewer.canOpen(file("py")))
        assertFalse(viewer.canOpen(dir.resolve("compiled.py").apply { writeBytes(byteArrayOf(0x61, 0x00, 0x62)) }))
        assertFalse(viewer.canOpen(dir.resolve("missing.py")))
    }

    @Test
    fun `is chosen before the default text viewer`() {
        val fallback = DefaultTextFileViewer()
        val viewers = FileViewers(listOf(fallback, viewer), fallback)

        assertSame(viewer, viewers.viewerFor(file("main.py")))
        assertSame(fallback, viewers.viewerFor(file("notes.txt")))
    }
}
