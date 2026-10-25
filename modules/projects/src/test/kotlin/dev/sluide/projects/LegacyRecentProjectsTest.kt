package dev.sluide.projects

import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegacyRecentProjectsTest {
    @TempDir
    lateinit var dir: Path

    private val legacy by lazy { LegacyRecentProjects(dir.resolve("projects.properties")) }

    private fun store(vararg migrations: LegacyRecentProjects) =
        ProjectDataStore(dir.resolve("projects"), dir.resolve("outside-projects.json"), migrations.toList())

    private fun folder(name: String) = dir.resolve(name).createDirectories()

    @Test
    fun `migrating writes lastOpened`() {
        val store = store()
        val a = folder("a")

        assertTrue(legacy.migrate(mapOf(a.toString() to "5000"), store))

        assertEquals(5000, store.read(a)?.get("lastOpened")?.asLong())
    }

    @Test
    fun `the larger time wins`() {
        val store = store()
        val a = folder("a")
        val b = folder("b")
        store.update(a) { it.put("lastOpened", 9000L) }
        store.update(b) { it.put("lastOpened", 1000L) }

        legacy.migrate(mapOf(a.toString() to "5000", b.toString() to "5000"), store)

        assertEquals(9000, store.read(a)?.get("lastOpened")?.asLong())
        assertEquals(5000, store.read(b)?.get("lastOpened")?.asLong())
    }

    @Test
    fun `invalid entries are skipped`() {
        val store = store()
        val a = folder("a")

        val complete = legacy.migrate(mapOf(a.toString() to "yesterday", "bad\u0000path" to "5"), store)

        assertTrue(complete)
        assertEquals(emptyList(), store.projects())
    }

    @Test
    fun `a failing write reports incomplete`() {
        dir.resolve("projects").writeText("not a folder")

        assertFalse(legacy.migrate(mapOf(folder("a").toString() to "5"), store()))
    }

    @Test
    fun `the old file is migrated and deleted by the store`() {
        val a = folder("a")
        val b = folder("b")
        legacy.file.writeText("$a=1000\n$b=2000\n")

        val recent = RecentProjects(store(legacy))

        assertEquals(listOf("b", "a"), recent.list().map { it.name })
        assertEquals(Instant.ofEpochMilli(2000), recent.list().first().lastOpened)
        assertFalse(legacy.file.exists())
    }
}
