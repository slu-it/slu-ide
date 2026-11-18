package dev.sluide.projectdata

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyMigrationTest {
    @TempDir
    lateinit var dir: Path

    private val legacy by lazy { dir.resolve("projects.properties") }

    /** Writes each entry as a project's `lastOpened`, unless it is there; reports [complete]. */
    private inner class FakeLegacy(
        private val complete: Boolean = true,
    ) : LegacyProjectData {
        var calls = 0

        override val file: Path get() = legacy

        override fun migrate(
            entries: Map<String, String>,
            store: ProjectDataStore,
        ): Boolean {
            calls++
            entries.forEach { (folder, millis) ->
                store.update(Path.of(folder)) { if (!it.has("lastOpened")) it.put("lastOpened", millis.toLong()) }
            }
            return complete
        }
    }

    private fun store(migration: LegacyProjectData) =
        ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), listOf(migration))

    private fun writeLegacy(vararg folders: Path) =
        legacy.writeText(folders.withIndex().joinToString("\n") { (i, folder) -> "${escaped(folder)}=${i + 1}" })

    // properties files escape ':' and '\' in keys
    private fun escaped(folder: Path) = folder.toString().replace("\\", "\\\\").replace(":", "\\:")

    @Test
    fun `the old file is migrated before the first read and then deleted`() {
        val a = dir.resolve("a")
        val b = dir.resolve("b")
        writeLegacy(a, b)

        val projects = store(FakeLegacy()).projects()

        assertEquals(listOf(a to 1L, b to 2L), projects.map { it.folder to it.data.get("lastOpened").longValue() })
        assertFalse(legacy.exists())
    }

    @Test
    fun `an incomplete migration keeps the old file and runs again at the next start`() {
        writeLegacy(dir.resolve("a"))
        val migration = FakeLegacy(complete = false)

        store(migration).projects()
        assertTrue(legacy.exists())
        store(migration).projects()

        assertEquals(2, migration.calls)
        assertTrue(legacy.exists())
    }

    @Test
    fun `an old file with a malformed escape is kept and not migrated`() {
        legacy.writeText("key=\\uZZZZ")
        val migration = FakeLegacy()

        assertEquals(emptyList(), store(migration).projects())

        assertEquals(0, migration.calls)
        assertEquals("key=\\uZZZZ", legacy.readText())
    }

    @Test
    fun `without an old file nothing is migrated`() {
        val migration = FakeLegacy()

        LogCapture(ProjectDataStore::class.java.name).use { log ->
            store(migration).projects()

            assertEquals(emptyList(), log.messages())
        }

        assertEquals(0, migration.calls)
    }

    @Test
    fun `the migration runs once per store, also when the first access removes a project`() {
        val a = dir.resolve("a")
        writeLegacy(a)
        val migration = FakeLegacy()
        val store = store(migration)

        store.remove(a)
        store.update(dir.resolve("b")) { it.put("lastOpened", 3) }
        store.fileData(dir.resolve("b/x.txt"), dir.resolve("b"))

        assertEquals(1, migration.calls)
        assertNull(store.read(a))
        assertEquals(listOf(dir.resolve("b")), store.projects().map { it.folder })
    }

    @Test
    fun `whatever the first access is, the migration runs before it`() {
        val first: Map<String, (ProjectDataStore) -> Unit> =
            mapOf(
                "projects" to { it.projects() },
                "read" to { assertEquals(1L, it.read(dir.resolve("a"))?.get("lastOpened")?.longValue()) },
                "update" to { it.update(dir.resolve("a")) {} },
                "readShared" to { it.readShared() },
                "updateShared" to { it.updateShared {} },
                "remove" to { it.remove(dir.resolve("a")) },
                "fileData" to { it.fileData(dir.resolve("a/x"), dir.resolve("a")) },
                "updateFileData" to { it.updateFileData(dir.resolve("a/x"), dir.resolve("a")) {} },
                "moved" to { it.moved(dir.resolve("a/x"), dir.resolve("a/y"), dir.resolve("a")) },
                "deleted" to { it.deleted(dir.resolve("a/x"), dir.resolve("a")) },
            )

        first.entries.forEachIndexed { index, (name, access) ->
            writeLegacy(dir.resolve("a"))
            val migration = FakeLegacy()

            access(ProjectDataStore(dir.resolve("p$index"), dir.resolve("s$index.json"), listOf(migration)))

            assertEquals(1, migration.calls, name)
            assertFalse(legacy.exists(), name)
        }
    }

    @Test
    fun `a migration that throws keeps its old file and does not stop the others or the store`() {
        val failing = dir.resolve("failing.properties").apply { writeText("a=1") }
        writeLegacy(dir.resolve("a"))
        val throwing =
            object : LegacyProjectData {
                override val file = failing

                override fun migrate(
                    entries: Map<String, String>,
                    store: ProjectDataStore,
                ): Boolean = error("boom")
            }
        val store =
            ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), listOf(throwing, FakeLegacy()))

        LogCapture(ProjectDataStore::class.java.name).use { log ->
            assertEquals(listOf(dir.resolve("a")), store.projects().map { it.folder })

            assertEquals(listOf(WARNING_LEVEL to "Migrating $failing failed"), log.lines.take(1))
            assertEquals("boom", log.thrown.first().message)
        }
        assertEquals("a=1", failing.readText())
        assertFalse(legacy.exists())
    }

    private fun assertSecondMigrationStillRuns(failing: LegacyProjectData) {
        writeLegacy(dir.resolve("a"))
        val migration = FakeLegacy()
        val store = ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), listOf(failing, migration))

        assertEquals(listOf(dir.resolve("a")), store.projects().map { it.folder })

        assertEquals(1, migration.calls)
        assertFalse(legacy.exists())
    }

    @Test
    fun `a migration whose file cannot be named does not escape the store`() {
        assertSecondMigrationStillRuns(
            object : LegacyProjectData {
                override val file: Path get() = error("no file")

                override fun migrate(
                    entries: Map<String, String>,
                    store: ProjectDataStore,
                ) = true
            },
        )
    }

    @Test
    fun `a migration that throws a checked exception keeps its old file and does not escape the store`() {
        val failing = dir.resolve("failing.properties").apply { writeText("a=1") }

        assertSecondMigrationStillRuns(
            object : LegacyProjectData {
                override val file = failing

                // Kotlin has no checked exceptions: a migration can throw an IOException
                override fun migrate(
                    entries: Map<String, String>,
                    store: ProjectDataStore,
                ): Boolean = throw java.io.IOException("disk")
            },
        )

        assertEquals("a=1", failing.readText())
    }

    @Test
    fun `an old file that is a folder is kept and not migrated`() {
        legacy.createDirectories()
        val migration = FakeLegacy()

        LogCapture(ProjectDataStore::class.java.name).use { log ->
            assertEquals(emptyList(), store(migration).projects())

            assertEquals(listOf("Cannot read $legacy to migrate it, keeping it"), log.messages())
        }
        assertEquals(0, migration.calls)
        assertTrue(legacy.isDirectory())
    }

    @Test
    fun `the outcome of a migration is logged`() {
        LogCapture(ProjectDataStore::class.java.name).use { log ->
            writeLegacy(dir.resolve("a"))
            store(FakeLegacy()).projects()
            writeLegacy(dir.resolve("b"))
            store(FakeLegacy(complete = false)).projects()
            legacy.writeText("key=\\uZZZZ")
            store(FakeLegacy()).projects()

            assertEquals(
                listOf(
                    INFO_LEVEL to "Migrated $legacy into the project data",
                    WARNING_LEVEL to "Could not migrate all of $legacy, keeping it for the next start",
                    WARNING_LEVEL to "Cannot read $legacy to migrate it, keeping it",
                ),
                log.lines.toList(),
            )
        }
    }

    private companion object {
        val WARNING_LEVEL: java.util.logging.Level = java.util.logging.Level.WARNING
        val INFO_LEVEL: java.util.logging.Level = java.util.logging.Level.INFO
    }
}
