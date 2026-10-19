package dev.sluide.ide.logging

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.isDirectory
import kotlin.io.path.setPosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogFolderTest {
    @TempDir
    lateinit var dir: Path

    private val fallback by lazy { dir.resolve("fallback") }

    @Test
    fun `a missing folder is created and used`() {
        val logs = dir.resolve(".sluide/logs")
        assertEquals(logs, logFolder(logs, fallback))
        assertTrue(logs.isDirectory())
    }

    @Test
    fun `a folder that is a file falls back`() {
        val logs = dir.resolve("logs").createFile()
        assertEquals(fallback, logFolder(logs, fallback))
    }

    @Test
    fun `a read-only folder falls back`() {
        val logs = dir.resolve("logs").createDirectories()
        logs.setPosixFilePermissions(PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assertEquals(fallback, logFolder(logs, fallback))
        } finally {
            logs.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `a read-only log file falls back`() {
        val logs = dir.resolve("logs").createDirectories()
        logs.resolve("sluide.log").createFile().setPosixFilePermissions(PosixFilePermissions.fromString("r--r--r--"))
        assertEquals(fallback, logFolder(logs, fallback))
    }

    @Test
    fun `a log file that is a folder falls back`() {
        val logs = dir.resolve("logs").createDirectories()
        logs.resolve("sluide.log").createDirectories()
        assertEquals(fallback, logFolder(logs, fallback))
    }

    @Test
    fun `only changes inside the log folder are log files`() {
        val logs = dir.resolve("logs").createDirectories()
        val folder = LogFolderPaths(logs)

        assertTrue(folder.onlyLogFiles(setOf(logs.resolve("sluide.log"), logs.resolve("sluide.1.log"))))
        assertFalse(folder.onlyLogFiles(setOf(logs.resolve("sluide.log"), dir.resolve("src/a.kt"))))
        assertFalse(folder.onlyLogFiles(setOf(dir.resolve("logs-other/a.log"))))
        // Unknown (F5): refresh anyway.
        assertFalse(folder.onlyLogFiles(emptySet()))
    }

    @Test
    fun `files are matched by the real path of a linked log folder`() {
        val real = dir.resolve("real").createDirectories()
        val link = Files.createSymbolicLink(dir.resolve("link"), real)

        val folder = LogFolderPaths(link)

        assertTrue(folder.onlyLogFiles(setOf(real.resolve("sluide.log"))))
        assertTrue(folder.onlyLogFiles(setOf(link.resolve("sluide.log"))))
    }
}
