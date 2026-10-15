package dev.sluide.projects

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecentProjectsTest {
    @TempDir
    lateinit var dir: Path

    private val t0 = Instant.ofEpochSecond(1_000)

    private fun store() = RecentProjects(dir.resolve("config/projects.properties"))

    private fun folder(name: String) = dir.resolve(name).createDirectories()

    @Test
    fun `lists newest first`() {
        val store = store()
        store.opened(folder("a"), t0)
        store.opened(folder("b"), t0.plusSeconds(20))
        store.opened(folder("c"), t0.plusSeconds(10))

        assertEquals(listOf("b", "c", "a"), store.list().map { it.name })
    }

    @Test
    fun `reopening refreshes the timestamp and reorders`() {
        val store = store()
        store.opened(folder("a"), t0)
        store.opened(folder("b"), t0.plusSeconds(10))
        store.opened(folder("a"), t0.plusSeconds(20))

        val list = store.list()
        assertEquals(listOf("a", "b"), list.map { it.name })
        assertEquals(t0.plusSeconds(20), list.first().lastOpened)
    }

    @Test
    fun `remove drops the entry but not the folder`() {
        val store = store()
        val a = folder("a")
        store.opened(a, t0)
        store.opened(folder("b"), t0.plusSeconds(1))

        store.remove(a)

        assertEquals(listOf("b"), store.list().map { it.name })
        assertTrue(Files.isDirectory(a))
    }

    @Test
    fun `entries persist across instances`() {
        store().opened(folder("a"), t0)

        assertEquals(listOf(folder("a")), store().list().map { it.folder })
    }

    @Test
    fun `a missing file means no projects`() {
        assertEquals(emptyList(), store().list())
    }

    @Test
    fun `an unreadable file gives no projects and is not replaced`() {
        val store = store()
        store.file.parent.createDirectories()
        store.file.writeText("a=\\uZZZZ")

        assertEquals(emptyList(), store.list())
        store.opened(folder("a"), t0)

        assertEquals("a=\\uZZZZ", store.file.readText())
    }

    @Test
    fun `entries with an unparsable time are skipped`() {
        val store = store()
        store.opened(folder("a"), t0)
        store.file.writeText(store.file.readText() + "/x/y=yesterday\n")

        assertEquals(listOf("a"), store.list().map { it.name })
    }

    @Test
    fun `paths are made absolute and normalized`() {
        val store = store()
        val a = folder("a")
        store.opened(dir.resolve("b/../a"), t0)
        store.opened(a, t0.plusSeconds(1))

        assertEquals(listOf(a), store.list().map { it.folder })
    }

    @Test
    fun `display path is shortened below home`() {
        val home = dir.resolve("home")
        val inside = RecentProject(home.resolve("a/b"), t0)

        assertEquals("~", RecentProject(home, t0).displayPath(home))
        assertEquals("~/a/b", inside.displayPath(home))
        assertEquals(dir.resolve("other").toString(), RecentProject(dir.resolve("other"), t0).displayPath(home))
        assertEquals("b", inside.name)
    }
}
