package dev.sluide.projectdata

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileLocationTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the project folder itself is not a file in it, its data goes to the shared file`() {
        val project = dir.resolve("project")

        assertEquals(
            FileLocation(null, project.invariantSeparatorsPathString),
            locate(project.resolve("sub/.."), project),
        )
    }

    @Test
    fun `a key stands for an absolute file, relative to the project or not, and for none otherwise`() {
        val project = dir.resolve("project")

        assertEquals(project.resolve("b"), fileOf(project, "a/../b"))
        assertEquals(dir.resolve("other"), fileOf(project, dir.resolve("x/../other").toString()))
        assertEquals(dir.resolve("b"), fileOf(null, dir.resolve("a/../b").toString()))
        assertNull(fileOf(null, "relative/a.txt"))
        assertNull(fileOf(null, "bad\u0000key"))
        assertNull(fileOf(project, "bad\u0000key"))
    }
}
