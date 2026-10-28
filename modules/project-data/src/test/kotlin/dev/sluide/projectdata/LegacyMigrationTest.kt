package dev.sluide.projectdata

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
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
    fun `an unreadable old file is kept and not migrated`() {
        legacy.writeText("key=\\uZZZZ")
        val migration = FakeLegacy()

        assertEquals(emptyList(), store(migration).projects())

        assertEquals(0, migration.calls)
        assertEquals("key=\\uZZZZ", legacy.readText())
    }

    @Test
    fun `without an old file nothing is migrated`() {
        val migration = FakeLegacy()

        store(migration).projects()

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
}
