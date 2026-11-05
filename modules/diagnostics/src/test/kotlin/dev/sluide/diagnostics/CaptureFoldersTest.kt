package dev.sluide.diagnostics

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureFoldersTest {
    @TempDir
    lateinit var temp: Path

    private val time = ZonedDateTime.of(2026, 11, 5, 9, 5, 7, 0, ZoneOffset.UTC)

    @Test
    fun `names a capture folder by its time and never reuses one`() {
        val base = temp.resolve("diagnostics")

        val first = createCaptureFolder(base, time)
        val second = createCaptureFolder(base, time)
        val third = createCaptureFolder(base, time)

        assertEquals(
            listOf("2026-11-05_09-05-07", "2026-11-05_09-05-07-2", "2026-11-05_09-05-07-3"),
            listOf(first, second, third).map { it.name },
        )
        assertTrue(listOf(first, second, third).all { Files.isDirectory(it) })
    }

    @Test
    fun `capture folders are for the owner only`() {
        assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews(), "POSIX permissions only")

        val folder = createCaptureFolder(temp.resolve("diagnostics"), time)

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(folder)))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(folder.parent)))
    }

    @Test
    fun `keeps the newest capture folders and nothing else is touched`() {
        val captures =
            (1..12).map { day ->
                temp.resolve(String.format("2026-10-%02d_12-00-00", day)).createDirectories().also {
                    it.resolve("report.txt").writeText("report")
                }
            }
        val sameSecond = temp.resolve("2026-10-12_12-00-00-2").createDirectories()
        val tenth = temp.resolve("2026-10-12_12-00-00-10").createDirectories()
        val other = temp.resolve("notes").createDirectories()
        val file = temp.resolve("2026-01-01_00-00-00.txt").also { it.writeText("mine") }
        val namedLikeOne = temp.resolve("2026-01-01_00-00-00").also { it.writeText("a file, not a folder") }

        applyRetention(temp, keep = 10)

        val kept = temp.listDirectoryEntries().map { it.name }.toSet()
        assertEquals(
            setOf(tenth, sameSecond).map { it.name }.toSet() + captures.drop(4).map { it.name } +
                setOf(other.name, file.name, namedLikeOne.name),
            kept,
        )
        assertTrue(captures.take(4).none { it.exists() })
    }

    @Test
    fun `retention never throws`() {
        applyRetention(temp.resolve("missing"), keep = 10)
    }

    @Test
    fun `writes a report through a temp file`() {
        val target = temp.resolve("report.txt")

        writeAtomically(target, "first")
        writeAtomically(target, "second")

        assertEquals("second", target.readText())
        assertEquals(listOf("report.txt"), temp.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `reads the last lines of a log`() {
        val log = temp.resolve("sluide.log")
        log.writeText((1..1000).joinToString("") { "line $it ${"x".repeat(200)}\r\n" })

        val lines = tailLines(log, 200)

        assertEquals(200, lines.size)
        assertEquals("line 801 ${"x".repeat(200)}", lines.first())
        assertEquals("line 1000 ${"x".repeat(200)}", lines.last())
    }

    @Test
    fun `reads a short log whole`() {
        val log = temp.resolve("short.log")
        log.writeText("one\ntwo")
        val empty = temp.resolve("empty.log").also { it.writeText("") }

        assertEquals(listOf("one", "two"), tailLines(log, 200))
        assertEquals(emptyList(), tailLines(empty, 200))
    }
}
