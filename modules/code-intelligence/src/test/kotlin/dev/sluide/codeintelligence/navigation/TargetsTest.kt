package dev.sluide.codeintelligence.navigation

import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.codeintelligence.ui.PopupCell
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/** How places show in a list, the order of usages, and the line texts read from disk. */
class TargetsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a place shows its file and line, then the path relative to the project or absolute outside, and its text`() {
        val project = Path.of("/work/project")
        val inside = SourcePosition(project.resolve("pkg/mod.py"), 11, 4)
        assertEquals(PopupCell("mod.py:12", "pkg/mod.py"), targetCell(NavigationTarget(inside), project))
        assertEquals(
            PopupCell("mod.py:12", "pkg/mod.py  def run():"),
            targetCell(NavigationTarget(inside, "def run():"), project),
        )
        val outside = SourcePosition(Path.of("/usr/lib/python3/posixpath.py"), 0, 0)
        assertEquals(
            PopupCell("posixpath.py:1", "/usr/lib/python3/posixpath.py"),
            targetCell(NavigationTarget(outside, ""), project),
        )
        assertEquals("/usr/lib/python3/posixpath.py", shownPath(outside.file, null))
    }

    @Test
    fun `usages list the current file first, then by path, line and column, without repeats`() {
        val current = Path.of("/p/z.py")
        val a = Path.of("/p/a.py")
        val positions =
            listOf(
                SourcePosition(a, 3, 0),
                SourcePosition(current, 9, 2),
                SourcePosition(a, 1, 5),
                SourcePosition(current, 9, 1),
                SourcePosition(a, 1, 5),
            )
        assertEquals(
            listOf(
                SourcePosition(current, 9, 1),
                SourcePosition(current, 9, 2),
                SourcePosition(a, 1, 5),
                SourcePosition(a, 3, 0),
            ),
            sortedUsages(positions, current),
        )
    }

    @Test
    fun `line texts are read trimmed, skipping files too large, missing or unreadable as text`() {
        val small = dir.resolve("small.py").also { it.writeText("first\n    second()  \r\nthird") }
        val large = dir.resolve("large.py").also { it.writeText("x".repeat(64)) }
        val binary = dir.resolve("bad.py").also { it.writeBytes(byteArrayOf('a'.code.toByte(), 0xff.toByte())) }
        val positions =
            listOf(
                SourcePosition(small, 1, 4),
                SourcePosition(small, 2, 0),
                SourcePosition(small, 7, 0),
                SourcePosition(large, 0, 0),
                SourcePosition(dir.resolve("gone.py"), 0, 0),
                SourcePosition(binary, 0, 0),
            )
        val texts = readLineTexts(positions, maxBytes = 32)
        assertEquals(
            mapOf(positions[0] to "second()", positions[1] to "third", positions[5] to "a�"),
            texts,
        )
    }

    @Test
    fun `a read whose handle was closed, before or after the disk, tells nothing`() {
        val file = dir.resolve("a.py").also { it.writeText("x = 1\n") }
        val position = SourcePosition(file, 0, 0)
        val background = mutableListOf<Runnable>()
        val fxQueue = mutableListOf<Runnable>()
        val lines = DiskLines(background::add, fxQueue::add)
        val told = mutableListOf<Map<SourcePosition, String>>()

        lines.read(listOf(position)) { told += it }.close()
        background.removeFirst().run()
        assertEquals(emptyList(), fxQueue, "dropped before reading: nothing to tell")

        val handle = lines.read(listOf(position)) { told += it }
        background.removeFirst().run()
        handle.close()
        fxQueue.removeFirst().run()
        assertEquals(emptyList(), told)

        lines.read(listOf(position)) { told += it }
        background.removeFirst().run()
        fxQueue.removeFirst().run()
        assertEquals(listOf(mapOf(position to "x = 1")), told)
    }
}
