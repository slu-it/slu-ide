package dev.sluide.projectdata

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What happens to the files when a write is refused, fails or changes nothing, and what the store logs about it. */
class ProjectDataStoreWriteTest {
    @TempDir
    lateinit var dir: Path

    private val projects by lazy { dir.resolve("projects") }
    private val shared by lazy { dir.resolve("shared.json") }
    private val project by lazy { dir.resolve("project").createDirectories() }

    private fun store() = ProjectDataStore(projects, shared, emptyList())

    private fun quoted(path: Path) = mapper.writeValueAsString(path.toString())

    private fun projectFile(
        name: String,
        content: String,
    ): Path = projects.createDirectories().resolve(name).apply { writeText(content) }

    private fun json(file: Path) = (readJson(file) as JsonRead.Found).node

    private class Unserializable {
        @Suppress("unused")
        val value: String get() = error("boom")
    }

    private fun <T> withPermissions(
        folder: Path,
        permissions: String,
        usable: () -> Boolean,
        body: () -> T,
    ): T {
        folder.createDirectories()
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString(permissions))
        try {
            assumeTrue(!usable(), "the folder is usable anyway (running as root?)")
            return body()
        } finally {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    private fun <T> readOnly(
        folder: Path,
        body: () -> T,
    ) = withPermissions(folder, "r-xr-xr-x", { Files.isWritable(folder) }, body)

    @Test
    fun `a change sees the path and version of a new file and cannot alter them, there or in an existing one`() {
        var seen: Pair<String?, Int?>? = null
        val evil: (tools.jackson.databind.node.ObjectNode) -> Unit = {
            it.put("path", "/evil").put("version", 9)
        }

        store().update(project) { seen = it.get("path")?.stringValue() to it.get("version")?.intValue() }
        assertEquals(project.toString() to 1, seen)
        store().update(project, evil)
        val other = dir.resolve("other").createDirectories()
        store().update(other, evil)

        assertEquals(
            setOf(project.toString(), other.toString()),
            store().projects().map { it.folder.toString() }.toSet(),
        )
        projects.listDirectoryEntries("*.json").forEach { assertEquals(1, json(it).get("version").intValue()) }
    }

    @Test
    fun `a change that alters nothing writes nothing`() {
        val content = """{"version":1,"path":${quoted(project)},"a":1}"""
        val file = projectFile("p.json", content)
        shared.writeText("""{"version":1,"a":1}""")
        val store = store()

        assertTrue(store.update(project) { it.put("a", 1) })
        assertTrue(store.updateShared { it.put("a", 1) })

        assertEquals(content, file.readText())
        assertEquals("""{"version":1,"a":1}""", shared.readText())
    }

    @Test
    fun `a project file that cannot be replaced keeps its content and a new one is not created`() {
        val content = """{"version":1,"path":${quoted(project)},"a":1}"""
        val file = projectFile("p.json", content)
        val other = dir.resolve("other").createDirectories()

        readOnly(projects) {
            val store = store()
            assertFalse(store.update(project) { it.put("a", 2) })
            assertFalse(store.update(other) { it.put("a", 2) })
            assertFalse(store.updateFileData(project.resolve("x.txt"), project) { it.put("a", 2) })
        }

        assertEquals(content, file.readText())
        assertEquals(listOf(file), projects.listDirectoryEntries())
    }

    @Test
    fun `a project folder that cannot be created fails the update`() {
        projects.writeText("a file, not a folder")

        assertFalse(store().update(project) { it.put("a", 1) })

        assertEquals("a file, not a folder", projects.readText())
    }

    @Test
    fun `a shared file that cannot be replaced keeps its content, a change that alters nothing still succeeds`() {
        val content = """{"version":1,"a":1}"""
        shared.writeText(content)

        readOnly(dir) {
            val store = store()
            assertFalse(store.updateShared { it.put("a", 2) })
            assertFalse(store.updateFileData(dir.resolve("x.txt"), null) { it.put("a", 2) })
            assertTrue(store.updateShared { it.put("a", 1) })
        }

        assertEquals(content, shared.readText())
    }

    @Test
    fun `remove that cannot delete keeps the file, logs it and does not throw`() {
        val file = projectFile("p.json", """{"version":1,"path":${quoted(project)}}""")

        LogCapture(ProjectDataStore::class.java.name).use { log ->
            readOnly(projects) { store().remove(project) }

            assertTrue(log.messages().any { it == "Cannot delete the project data $file" }, log.messages().toString())
        }
        assertTrue(Files.exists(file))
    }

    @Test
    fun `remove of a project that is not there deletes nothing and does not throw`() {
        val kept = projectFile("p.json", """{"version":1,"path":${quoted(project)}}""")

        store().remove(dir.resolve("unknown"))
        ProjectDataStore(dir.resolve("none"), dir.resolve("none.json"), emptyList()).remove(project)

        assertTrue(Files.exists(kept))
    }

    @Test
    fun `a change that cannot be serialized fails and keeps the old file`() {
        val content = """{"version":1,"path":${quoted(project)}}"""
        val file = projectFile("p.json", content)
        val other = dir.resolve("other").createDirectories()
        val store = store()

        assertFalse(store.update(project) { it.putPOJO("bad", Unserializable()) })
        assertFalse(store.update(other) { it.putPOJO("bad", Unserializable()) })

        assertEquals(content, file.readText())
        assertEquals(listOf(file), projects.listDirectoryEntries())
    }

    @Test
    fun `a file without a version is written and gets ours`() {
        val file = projectFile("p.json", """{"path": ${quoted(project)}, "a": 0}""")
        shared.writeText("""{"a": 0}""")
        val store = store()

        assertTrue(store.update(project) { it.put("a", 1) })
        assertTrue(store.updateShared { it.put("a", 1) })

        assertEquals(1, json(file).get("version").intValue())
        assertEquals(1, json(file).get("a").intValue())
        assertEquals(1, json(shared).get("version").intValue())
        assertEquals(1, json(shared).get("a").intValue())
    }

    @Test
    fun `a shared file that is a folder is never replaced`() {
        shared.createDirectories()

        assertFalse(store().updateShared { it.put("a", 1) })

        assertTrue(Files.isDirectory(shared))
    }

    @Test
    fun `a project folder that cannot be listed gives no projects and no error`() {
        projectFile("p.json", """{"version":1,"path":${quoted(project)}}""")

        LogCapture(ProjectDataStore::class.java.name).use { log ->
            withPermissions(projects, "--x--x--x", { Files.isReadable(projects) }) {
                val store = store()
                assertEquals(emptyList(), store.projects())
                assertNull(store.read(project))
                assertEquals(emptyList(), store.projects())
            }

            // logged once, not on every access
            assertEquals(
                1,
                log.messages().count { it == "Cannot list the project data in $projects" },
                log.messages().toString(),
            )
        }
    }

    @Test
    fun `no project file is created while the folder cannot be listed, so no earlier data is hidden`() {
        val content = """{"version":1,"path":${quoted(project)},"trust":"kept"}"""
        val file = projectFile("p.json", content)
        val other = dir.resolve("other").createDirectories()

        LogCapture(ProjectDataStore::class.java.name).use { log ->
            withPermissions(projects, "-wx------", { Files.isReadable(projects) }) {
                val store = store()
                assertFalse(store.update(project) { it.put("a", 1) })
                assertFalse(store.update(other) { it.put("a", 1) })
                assertFalse(store.updateFileData(project.resolve("x.txt"), project) { it.put("a", 1) })
            }

            assertEquals(
                1,
                log.messages().count { it == "Not creating a project file in $projects, it cannot be listed" },
                log.messages().toString(),
            )
        }

        assertEquals(listOf(file), projects.listDirectoryEntries())
        assertEquals(content, file.readText())
    }

    @Test
    fun `of files with the same time the one with the smallest name wins, else the newest wins whatever its name`() {
        // many files, so that the folder's listing order is unlikely to be the name order by chance
        val files =
            (0 until 12).reversed().map { n ->
                projectFile("f%02d.json".format(n), """{"version": 1, "path": ${quoted(project)}, "n": $n}""")
            }
        files.forEach { Files.setLastModifiedTime(it, FileTime.fromMillis(1_000_000)) }

        assertEquals(0, store().read(project)?.get("n")?.intValue())

        Files.setLastModifiedTime(files.first(), FileTime.fromMillis(2_000_000))

        assertEquals(11, store().read(project)?.get("n")?.intValue())
    }

    @Test
    fun `nothing is logged for a store without files, a folder named like a data file or a missing legacy file`() {
        projects.resolve("folder.json").createDirectories()

        LogCapture(ProjectDataStore::class.java.name).use { log ->
            ProjectDataStore(dir.resolve("none"), dir.resolve("none.json"), emptyList()).projects()
            val store = store()
            store.projects()
            store.readShared()

            assertEquals(emptyList(), log.messages())
        }
    }

    @Test
    fun `a broken file is reported once per store, not on every access`() {
        val broken = projectFile("broken.json", """{"version": 1}""")
        val unreadable = projectFile("unreadable.json", "{")
        val first = projectFile("a.json", """{"version": 1, "path": ${quoted(project)}}""")
        val second = projectFile("b.json", """{"version": 1, "path": ${quoted(project)}}""")
        shared.writeText("{")
        val store = store()

        LogCapture(ProjectDataStore::class.java.name).use { log ->
            repeat(3) {
                store.projects()
                store.updateShared { it.put("a", 1) }
            }

            val messages = log.messages()
            assertEquals(1, messages.count { it == "No project path in $broken, skipping it" }, messages.toString())
            assertEquals(
                1,
                messages.count { it == "Cannot read the project data in $unreadable, skipping it" },
                messages.toString(),
            )
            assertEquals(
                1,
                messages.count { it == "Several files for $project: ${listOf(first, second)}, the newest wins" } +
                    messages.count { it == "Several files for $project: ${listOf(second, first)}, the newest wins" },
                messages.toString(),
            )
            assertEquals(1, messages.count { it == "Not replacing the unreadable $shared" }, messages.toString())
            // the unreadable project file and the unreadable shared file have a cause, the other two warnings none
            assertEquals(2, log.thrown.size)
        }
    }

    @Test
    fun `a file of a newer version is reported each time it is refused`() {
        val file = projectFile("p.json", """{"version": 2, "path": ${quoted(project)}}""")
        shared.writeText("""{"version": 2}""")
        val store = store()

        LogCapture(ProjectDataStore::class.java.name).use { log ->
            repeat(2) { store.update(project) { it.put("a", 1) } }
            repeat(2) { store.updateShared { it.put("a", 1) } }

            assertEquals(
                listOf(
                    "Not replacing $file, written by a newer version",
                    "Not replacing $file, written by a newer version",
                    "Not replacing $shared, written by a newer version",
                    "Not replacing $shared, written by a newer version",
                ),
                log.messages(),
            )
        }
    }

    @Test
    fun `edits from several threads are not lost`() {
        val store = store()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs =
                (0 until 8).map { thread ->
                    pool.submit {
                        repeat(15) {
                            store.update(project) { data -> data.put("n", (data.get("n")?.intValue() ?: 0) + 1) }
                            store.updateShared { data -> data.put("n", (data.get("n")?.intValue() ?: 0) + 1) }
                            store.updateFileData(project.resolve("f$thread.txt"), project) { data ->
                                data.put("n", (data.get("n")?.intValue() ?: 0) + 1)
                            }
                        }
                    }
                }
            jobs.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(120, store.read(project)?.get("n")?.intValue())
        assertEquals(120, store.readShared()?.get("n")?.intValue())
        assertEquals(15, store.fileData(project.resolve("f3.txt"), project)?.get("n")?.intValue())
        assertEquals(1, projects.listDirectoryEntries("*.json").size)
    }
}
