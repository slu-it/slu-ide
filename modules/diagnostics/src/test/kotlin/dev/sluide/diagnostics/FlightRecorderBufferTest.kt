package dev.sluide.diagnostics

import jdk.jfr.consumer.RecordingFile
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlightRecorderBufferTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `dumps its events without the ones that can hold secrets`() {
        val buffer = FlightRecorderBuffer()
        try {
            buffer.start()
            buffer.start()
            assertNull(buffer.unavailable)
            // A process started with a secret in its arguments, as `git ls-remote https://user:token@…` would be.
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            ProcessBuilder(java, "-Dtoken=secret-in-arguments", "-version").start().waitFor()

            val dumped = buffer.dump(temp.resolve("recording.jfr"))

            assertEquals(temp.resolve("recording.jfr"), dumped)
            val types = RecordingFile.readAllEvents(dumped).map { it.eventType.name }.toSet()
            assertTrue(types.isNotEmpty())
            // Recorded at the start of a recording, so they would be in this dump if they were enabled.
            assertTrue("jdk.OSInformation" in types, "a start event is there: $types")
            assertEquals(emptySet(), types.intersect(DISABLED_EVENTS.toSet()))
            assertFalse(
                temp
                    .resolve("recording.jfr")
                    .readBytes()
                    .decodeToString()
                    .contains("secret-in-arguments"),
            )
        } finally {
            buffer.stop()
        }
    }

    @Test
    fun `keeps its chunks in an owner-only folder and deletes stale ones of ended processes`() {
        val repository = temp.resolve("diagnostics/.jfr").createDirectories()
        val ended = 999_999_999L
        val stale = repository.resolve("2020_01_01_00_00_00_$ended").createDirectories()
        stale.resolve("chunk.jfr").writeText("old")
        val recent = repository.resolve("2020_01_02_00_00_00_$ended").createDirectories()
        val running = repository.resolve("2020_01_03_00_00_00_${ProcessHandle.current().pid()}").createDirectories()
        val other = repository.resolve("notes").createDirectories()
        val twoDaysAgo = FileTime.from(Instant.now().minus(Duration.ofDays(2)))
        for (folder in listOf(stale, running, other)) Files.setLastModifiedTime(folder, twoDaysAgo)
        val before = repository.listDirectoryEntries().toSet()
        val warnings = mutableListOf<String>()
        val buffer = FlightRecorderBuffer(repository, warn = { message, _ -> warnings += message })
        try {
            buffer.start()

            assertNull(buffer.unavailable)
            assertEquals(emptyList(), warnings, "JFR took the folder")
            assertFalse(stale.exists())
            assertTrue(recent.exists() && running.exists() && other.exists())
            assertTrue((repository.listDirectoryEntries() - before).isNotEmpty(), "JFR writes into it")
            if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
                assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(repository)))
            }
        } finally {
            buffer.stop()
        }
    }

    @Test
    fun `warns when JFR cannot use the folder and records all the same`() {
        val notAFolder = temp.resolve("diagnostics").also { it.writeText("a file") }
        val warnings = mutableListOf<String>()
        val unusable = FlightRecorderBuffer(notAFolder.resolve(".jfr"), warn = { message, _ -> warnings += message })
        // JFR.configure reports a path it cannot use in its output, not with an exception.
        val refused =
            FlightRecorderBuffer(
                temp.resolve("refused/.jfr"),
                configure = { "Could not use $it as repository. Not writable" },
                warn = { message, _ -> warnings += message },
            )
        try {
            unusable.start()
            unusable.stop()
            refused.start()

            assertNull(refused.unavailable)
            assertEquals(2, warnings.size, warnings.toString())
            assertTrue(warnings[0].startsWith("JFR keeps its chunks in its default folder: $notAFolder"))
            assertTrue(
                warnings[1].endsWith(
                    "could not be used: Could not use ${temp.resolve("refused/.jfr")} as repository. Not writable",
                ),
            )
        } finally {
            unusable.stop()
            refused.stop()
        }
    }

    @Test
    fun `gives no recording when it is not running`() {
        val buffer = FlightRecorderBuffer()

        assertNull(buffer.dump(temp.resolve("not-started.jfr")))
        assertEquals("the recorder is not running", buffer.unavailable)

        buffer.stop()
        buffer.start()

        assertNull(buffer.dump(temp.resolve("stopped.jfr")))
        assertEquals("the recorder was stopped", buffer.unavailable)
    }
}
