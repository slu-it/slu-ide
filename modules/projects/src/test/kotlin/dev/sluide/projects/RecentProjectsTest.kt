package dev.sluide.projects

import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecentProjectsTest {
    @TempDir
    lateinit var dir: Path

    private val t0 = Instant.ofEpochSecond(1_000)

    private fun data() = ProjectDataStore(dir.resolve("projects"), dir.resolve("outside-projects.json"), emptyList())

    private fun store() = RecentProjects(data())

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
    fun `remove forgets the whole project but not the folder`() {
        val data = data()
        val store = RecentProjects(data)
        val a = folder("a")
        store.opened(a, t0)
        data.update(a) { it.putObject("trust").put("trusted", true) }
        store.opened(folder("b"), t0.plusSeconds(1))

        store.remove(a)

        assertEquals(listOf("b"), store.list().map { it.name })
        assertNull(data.read(a))
        assertTrue(Files.isDirectory(a))
    }

    @Test
    fun `a lastOpened that is not a number is not listed`() {
        val data = data()
        data.update(folder("a")) { it.put("lastOpened", "yesterday") }

        assertEquals(emptyList(), RecentProjects(data).list())
    }

    @Test
    fun `entries persist across instances`() {
        store().opened(folder("a"), t0)

        assertEquals(listOf(folder("a")), store().list().map { it.folder })
    }

    @Test
    fun `a missing project folder means no projects`() {
        assertEquals(emptyList(), store().list())
    }

    @Test
    fun `a project with only other data is not listed`() {
        val data = data()
        data.update(folder("a")) { it.putObject("trust").put("trusted", true) }
        val store = RecentProjects(data)
        store.opened(folder("b"), t0)

        assertEquals(listOf("b"), store.list().map { it.name })
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
