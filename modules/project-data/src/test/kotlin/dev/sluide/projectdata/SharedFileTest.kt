package dev.sluide.projectdata

import dev.sluide.testsupport.LogCapture
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedFileTest {
    @TempDir
    lateinit var dir: Path

    private val shared by lazy { dir.resolve("shared.json") }
    private val old by lazy { dir.resolve("outside-projects.json") }

    private fun store() = ProjectDataStore(dir.resolve("projects"), shared, emptyList())

    @Test
    fun `updateShared creates the file with version 1 and readShared returns a copy`() {
        val store = store()
        assertNull(store.readShared())

        assertTrue(store.updateShared { it.put("todos", 3) })

        val read = store.readShared()!!
        assertEquals(1, read.get("version").intValue())
        assertEquals(3, read.get("todos").intValue())
        read.put("todos", 4)
        assertEquals(3, store.readShared()!!.get("todos").intValue())
    }

    @Test
    fun `unknown top-level fields and files round-trip and share the file with per-file data`() {
        shared.writeText("""{"version": 1, "other": {"a": [1]}, "files": {"/x": {"v": 1}}}""")
        val store = store()

        assertTrue(store.updateShared { it.put("todos", 1) })
        assertTrue(store.updateFileData(Path.of("/y"), null) { it.put("v", 2) })

        val read = store.readShared()!!
        assertEquals(
            1,
            read
                .get("other")
                .get("a")
                .get(0)
                .intValue(),
        )
        assertEquals(1, read.get("todos").intValue())
        assertEquals(setOf("/x", "/y"), read.get("files").propertyNames().toSet())
    }

    @Test
    fun `a change cannot remove or alter version and files`() {
        shared.writeText("""{"version": 1, "files": {"/x": {"v": 1}}}""")
        val store = store()

        assertTrue(
            store.updateShared {
                it.remove("version")
                it.remove("files")
                it.put("todos", 1)
            },
        )
        assertEquals(1, store.readShared()!!.get("version").intValue())
        assertEquals(1, store.fileData(Path.of("/x"), null)!!.get("v").intValue())

        assertTrue(store.updateShared { it.put("version", 7).putObject("files").putObject("/z") })
        assertEquals(1, store.readShared()!!.get("version").intValue())
        assertNull(store.fileData(Path.of("/z"), null))
    }

    @Test
    fun `a change without files does not add an empty files field`() {
        val store = store()

        store.updateShared { it.put("todos", 1) }

        assertFalse(store.readShared()!!.has("files"))
    }

    @Test
    fun `the old file is moved to the shared file and its data is readable`() {
        old.writeText("""{"version": 1, "files": {"/x": {"v": 1}}, "other": 5}""")
        val store = store()

        assertEquals(1, store.fileData(Path.of("/x"), null)!!.get("v").intValue())
        assertEquals(5, store.readShared()!!.get("other").intValue())
        assertFalse(old.exists())
    }

    @Test
    fun `with both files the shared file is kept and the old one untouched`() {
        val oldContent = """{"version": 1, "other": 1}"""
        old.writeText(oldContent)
        shared.writeText("""{"version": 1, "other": 2}""")

        val store = store()
        assertEquals(2, store.readShared()!!.get("other").intValue())
        store.updateShared { it.put("other", 3) }

        assertEquals(oldContent, old.readText())
    }

    @Test
    fun `the old file is moved even when the first access is a read of the shared file`() {
        old.writeText("""{"version": 1, "other": 5}""")

        assertEquals(5, store().readShared()!!.get("other").intValue())

        assertFalse(old.exists())
        assertTrue(shared.exists())
    }

    @Test
    fun `a change cannot add a files field to a file that has none`() {
        shared.writeText("""{"version": 1, "other": 1}""")
        val store = store()

        assertTrue(store.updateShared { it.putObject("files").putObject("/z") })

        assertFalse(store.readShared()!!.has("files"))
        assertNull(store.fileData(Path.of("/z"), null))
    }

    @Test
    fun `the move of the old file and the choice between both files are logged`() {
        LogCapture(ProjectDataStore::class.java.name).use { log ->
            old.writeText("""{"version": 1}""")
            store().readShared()
            old.writeText("""{"version": 1}""")
            store().readShared()

            assertEquals(
                listOf(
                    java.util.logging.Level.INFO to "Moved $old to $shared",
                    java.util.logging.Level.WARNING to "Both $old and $shared exist, using $shared and leaving $old",
                ),
                log.lines.toList(),
            )
        }
    }

    @Test
    fun `a shared file that already has the old name is not moved onto itself`() {
        val content = """{"version": 1, "other": 5}"""
        old.writeText(content)

        LogCapture(ProjectDataStore::class.java.name).use { log ->
            val store = ProjectDataStore(dir.resolve("projects"), old, emptyList())

            assertEquals(5, store.readShared()!!.get("other").intValue())
            assertEquals(emptyList(), log.messages())
        }
        assertEquals(content, old.readText())
    }

    @Test
    fun `an old file that cannot be moved stays for the next start`() {
        val content = """{"version": 1, "other": 5}"""
        old.writeText(content)
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assumeTrue(!Files.isWritable(dir), "the folder is writable anyway (running as root?)")

            LogCapture(ProjectDataStore::class.java.name).use { log ->
                assertNull(store().readShared())

                assertEquals(
                    listOf("Cannot move $old to $shared, keeping it for the next start"),
                    log.messages(),
                )
            }
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
        }

        assertEquals(content, old.readText())
        assertFalse(shared.exists())
        assertEquals(5, store().readShared()!!.get("other").intValue())
    }
}
