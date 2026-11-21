package dev.sluide.jshell

import dev.sluide.java.JavaHomes
import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JShellWindowTest {
    @TempDir
    lateinit var dir: Path

    private fun window(canChoose: (() -> Boolean)? = null): JShellWindow {
        val jdks =
            JShellJdks(ProjectDataStore(dir.resolve("p"), dir.resolve("s.json"), emptyList()), JavaHomes(), { null })
        return if (canChoose == null) {
            JShellWindow(jdks, { emptyList() }, {})
        } else {
            JShellWindow(jdks, { emptyList() }, {}, canChooseProjectJdk = canChoose)
        }
    }

    @Test
    fun `listeners hear that the project's JDK changed until they are closed`() {
        val window = window()
        val heard = mutableListOf<String>()
        val first = window.onProjectJdkChanged { heard += "first" }
        window.onProjectJdkChanged { heard += "second" }

        window.projectJdkChanged()
        first.close()
        window.projectJdkChanged()

        assertEquals(listOf("first", "second", "second"), heard)
    }

    @Test
    fun `a window can choose the project's JDK unless it says it cannot`() {
        assertTrue(window().canChooseProjectJdk())
        assertFalse(window { false }.canChooseProjectJdk())
    }

    @Test
    fun `the viewer takes jsh files only, and shows Java`() {
        val viewer = JShellFileViewer()
        val jsh = dir.resolve("a.JSH").apply { writeText("int x = 1;\n") }
        val java = dir.resolve("a.java").apply { writeText("class A {}\n") }
        val binary = dir.resolve("b.jsh").apply { writeBytes(ByteArray(64)) }

        assertTrue(viewer.canOpen(jsh))
        assertFalse(viewer.canOpen(java))
        assertFalse(viewer.canOpen(binary))
        assertEquals(setOf("jsh"), viewer.fileExtensions)
    }
}
