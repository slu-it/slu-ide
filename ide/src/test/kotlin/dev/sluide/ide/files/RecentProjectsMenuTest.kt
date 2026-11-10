package dev.sluide.ide.files

import dev.sluide.projects.RecentProject
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentProjectsMenuTest {
    private val home = Path.of("/home/me")

    private fun project(
        path: String,
        index: Int = 0,
    ) = RecentProject(Path.of(path), Instant.ofEpochSecond(1000L - index))

    @Test
    fun `leaves out the current project, also when given unnormalized`() {
        val projects = listOf(project("/work/a"), project("/work/b"))

        assertEquals(
            listOf(Path.of("/work/b")),
            recentEntries(projects, Path.of("/work/x/../a/"), home = home, exists = { true }).map { it.folder },
        )
        assertEquals(2, recentEntries(projects, null, home = home, exists = { true }).size)
    }

    @Test
    fun `keeps the 10 newest in order`() {
        val projects = (0 until 15).map { project("/work/p$it", it) }

        val entries = recentEntries(projects, Path.of("/work/p0"), home = home, exists = { true })

        assertEquals((1..10).map { Path.of("/work/p$it") }, entries.map { it.folder })
    }

    @Test
    fun `marks missing folders and labels with name and display path`() {
        val projects = listOf(project("/home/me/code/a"), project("/work/gone", 1))

        val entries = recentEntries(projects, null, home = home, exists = { it.fileName.toString() == "a" })

        assertEquals(
            listOf("a — ~/code/a" to true, "gone — /work/gone" to false),
            entries.map { it.label to it.exists },
        )
    }
}
