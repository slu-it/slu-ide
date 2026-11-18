package dev.sluide.projectdata

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileDataTest {
    @TempDir
    lateinit var dir: Path

    private val projects by lazy { dir.resolve("projects") }
    private val sharedFile by lazy { dir.resolve("shared.json") }
    private val project by lazy { dir.resolve("project").createDirectories() }

    private val store by lazy { ProjectDataStore(projects, sharedFile, emptyList()) }

    /** The keys of the per-file entries in the project's file. */
    private fun projectKeys(): List<String> = store.read(project)?.let(::keys) ?: emptyList()

    /** The keys of the per-file entries in the shared file. */
    private fun sharedKeys(): List<String> = (readJson(sharedFile) as? JsonRead.Found)?.node?.let(::keys) ?: emptyList()

    private fun keys(root: ObjectNode) = root.get("files")?.propertyNames()?.toList() ?: emptyList()

    private fun absolute(relative: String) = dir.resolve(relative).normalize().invariantSeparatorsPathString

    private fun remember(
        relative: String,
        value: String,
    ) = store.updateFileData(dir.resolve(relative), project) { it.put("value", value) }

    private fun value(relative: String) = store.fileData(dir.resolve(relative), project)?.get("value")?.stringValue()

    @ParameterizedTest(name = "{0} with project {1}")
    @CsvSource(
        "project/src/a.txt, project, src/a.txt",
        "project/src/../a.txt, project, a.txt",
        "other/a.txt, project, ",
        "project/a.txt, , ",
        "projectx/a.txt, project, ",
    )
    fun `a file's data lives in its project's file, else in the shared file`(
        file: String,
        projectFolder: String?,
        projectKey: String?,
    ) {
        val path = dir.resolve(file)
        val folder = projectFolder?.let { dir.resolve(it) }

        assertTrue(store.updateFileData(path, folder) { it.put("value", "v") })

        assertEquals("v", store.fileData(path, folder)?.get("value")?.stringValue())
        if (projectKey != null) {
            assertEquals(listOf(projectKey), projectKeys())
            assertEquals(emptyList(), sharedKeys())
        } else {
            assertEquals(emptyList(), projectKeys())
            assertEquals(listOf(absolute(file)), sharedKeys())
        }
    }

    @Test
    fun `a moved file takes its data along, replacing what was at the target`() {
        remember("project/a.txt", "a")
        remember("project/b.txt", "b")

        store.moved(dir.resolve("project/a.txt"), dir.resolve("project/b.txt"), project)

        assertNull(value("project/a.txt"))
        assertEquals("a", value("project/b.txt"))
        assertEquals(listOf("b.txt"), projectKeys())
    }

    @Test
    fun `a moved folder takes the data under it along, by path components`() {
        remember("project/a/b", "folder")
        remember("project/a/b/x.txt", "inside")
        remember("project/a/bc/y.txt", "beside")

        store.moved(dir.resolve("project/a/b"), dir.resolve("project/z"), project)

        assertEquals(setOf("z", "z/x.txt", "a/bc/y.txt"), projectKeys().toSet())
        assertEquals("folder", value("project/z"))
        assertEquals("inside", value("project/z/x.txt"))
        assertEquals("beside", value("project/a/bc/y.txt"))
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "outside/d/s.sh, project/d/s.sh",
        "project/d/s.sh, outside/d/s.sh",
        "outside/d, project/d",
        "project/d, outside/d",
    )
    fun `a move into or out of the project moves the data between the files`(
        from: String,
        to: String,
    ) {
        val intoProject = to.startsWith("project")
        val source = if (intoProject) "outside/d/s.sh" else "project/d/s.sh"
        val target = if (intoProject) "project/d/s.sh" else "outside/d/s.sh"
        remember(source, "script")

        store.moved(dir.resolve(from), dir.resolve(to), project)

        assertEquals("script", value(target))
        assertNull(value(source))
        if (intoProject) {
            assertEquals(listOf("d/s.sh"), projectKeys())
            assertEquals(emptyList(), sharedKeys())
        } else {
            assertEquals(emptyList(), projectKeys())
            assertEquals(listOf(absolute("outside/d/s.sh")), sharedKeys())
        }
    }

    @Test
    fun `a move of something without data changes nothing`() {
        remember("project/a.txt", "a")

        store.moved(dir.resolve("project/b.txt"), dir.resolve("project/c.txt"), project)

        assertEquals(listOf("a.txt"), projectKeys())
        assertFalse(sharedFile.toFile().exists())
    }

    @Test
    fun `a move drops the data stored at the target and under it in both files, also without data to move`() {
        remember("project/t", "stale")
        remember("project/t/u", "stale")
        remember("project/tu", "kept")
        store.updateFileData(dir.resolve("project/t/v"), null) { it.put("value", "stale") }

        store.moved(dir.resolve("project/a.txt"), dir.resolve("project/t"), project)

        assertEquals(listOf("tu"), projectKeys())
        assertEquals(emptyList(), sharedKeys())
    }

    @Test
    fun `a move out of the project keeps the entry in the project's file when the shared file cannot be written`() {
        remember("project/s.sh", "script")
        sharedFile.writeText("{broken")

        store.moved(dir.resolve("project/s.sh"), dir.resolve("out/s.sh"), project)

        assertEquals(listOf("s.sh"), projectKeys())
        assertEquals("{broken", sharedFile.readText())
    }

    @Test
    fun `a moved project folder takes its entries to the shared file`() {
        remember("project/a.txt", "a")

        store.moved(project, dir.resolve("moved"), project)

        assertEquals(emptyList(), projectKeys())
        assertEquals(listOf(absolute("moved/a.txt")), sharedKeys())
    }

    @ParameterizedTest
    @ValueSource(strings = ["{broken", "[]"])
    fun `an unreadable shared file is never replaced`(content: String) {
        sharedFile.writeText(content)
        val file = dir.resolve("out/a.txt")

        assertFalse(store.updateFileData(file, project) { it.put("value", "v") })
        assertFalse(store.updateShared { it.put("todos", 1) })
        store.moved(dir.resolve("out/b.txt"), file, project)
        store.deleted(file, project)

        assertNull(store.fileData(file, project))
        assertNull(store.readShared())
        assertEquals(content, sharedFile.readText())
    }

    @Test
    fun `a shared file of a newer version is read but never written`() {
        val file = dir.resolve("out/a.txt")
        val content = """{"version": 2, "files": {${mapper.writeValueAsString(
            absolute("out/a.txt"),
        )}: {"value": "v"}}}"""
        sharedFile.writeText(content)

        assertEquals("v", value("out/a.txt"))
        assertFalse(store.updateFileData(file, project) { it.put("value", "w") })
        assertFalse(store.updateShared { it.put("todos", 1) })
        store.deleted(file, project)

        assertEquals(2, store.readShared()?.get("version")?.intValue())
        assertEquals(content, sharedFile.readText())
    }

    @ParameterizedTest(name = "deleting {0}")
    @CsvSource(
        "project/a/b/x.txt, a/b;a/bc/y.txt, out/a/b/x.txt;out/a/bc",
        "project/a/b, a/bc/y.txt, out/a/b/x.txt;out/a/bc",
        "project/a/bc/y.txt, a/b;a/b/x.txt, out/a/b/x.txt;out/a/bc",
        "out/a/b, a/b;a/b/x.txt;a/bc/y.txt, out/a/bc",
    )
    fun `deleting a file or folder drops its data and the data under it`(
        deleted: String,
        projectRemaining: String,
        outsideRemaining: String,
    ) {
        listOf("project/a/b", "project/a/b/x.txt", "project/a/bc/y.txt", "out/a/b/x.txt", "out/a/bc").forEach {
            remember(it, it)
        }

        store.deleted(dir.resolve(deleted), project)

        assertEquals(projectRemaining.split(";").toSet(), projectKeys().toSet())
        assertEquals(outsideRemaining.split(";").map(::absolute).toSet(), sharedKeys().toSet())
    }

    @Test
    fun `a file's data that ends up empty is dropped`() {
        remember("project/a.txt", "a")
        remember("out/b.txt", "b")

        store.updateFileData(dir.resolve("project/a.txt"), project) { it.remove("value") }
        store.updateFileData(dir.resolve("out/b.txt"), project) { it.remove("value") }

        assertNull(store.fileData(dir.resolve("project/a.txt"), project))
        val projectRoot = (readJson(projects.listDirectoryEntries("*.json").single()) as JsonRead.Found).node
        assertNull(projectRoot.get("files"))
        assertNull((readJson(sharedFile) as JsonRead.Found).node.get("files"))
    }

    @Test
    fun `a move or delete leaves the entries whose key stands for no file alone`() {
        val bad = "bad\\u0000key"
        projects.createDirectories()
        val projectJson = projects.resolve("p.json")
        projectJson.writeText(
            """{"version": 1, "path": ${mapper.writeValueAsString(project.toString())},
               "files": {"$bad": {"v": 1}, "a.txt": {"v": 1}}}""",
        )
        sharedFile.writeText(
            """{"version": 1, "files": {"relative/a.txt": {"v": 1}, "$bad": {"v": 1},
               ${mapper.writeValueAsString(absolute("out/a.txt"))}: {"v": 1}}}""",
        )

        store.moved(dir.resolve("project/a.txt"), dir.resolve("project/c.txt"), project)
        store.moved(dir.resolve("out/a.txt"), dir.resolve("out/b.txt"), project)
        store.deleted(dir.resolve("project/c.txt"), project)
        store.deleted(dir.resolve("out"), project)

        assertEquals(listOf("bad\u0000key"), keys((readJson(projectJson) as JsonRead.Found).node))
        assertEquals(setOf("relative/a.txt", "bad\u0000key"), sharedKeys().toSet())
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = ["project/a.txt", "project/d", "out/a.txt"])
    fun `a move onto itself keeps the data`(moved: String) {
        remember("project/a.txt", "a")
        remember("project/d", "d")
        remember("project/d/x.txt", "x")
        remember("out/a.txt", "out")

        store.moved(dir.resolve(moved), dir.resolve(moved), project)

        assertEquals("a", value("project/a.txt"))
        assertEquals("d", value("project/d"))
        assertEquals("x", value("project/d/x.txt"))
        assertEquals("out", value("out/a.txt"))
    }

    @Test
    fun `a move between outside files creates no project file and keeps the project file's other data`() {
        sharedFile.writeText(
            """{"version": 1, "files": {${mapper.writeValueAsString(absolute("out/a.txt"))}: {"v": 1}}}""",
        )

        store.moved(dir.resolve("out/a.txt"), dir.resolve("out/b.txt"), project)
        store.deleted(dir.resolve("out/zzz"), project)

        assertEquals(listOf(absolute("out/b.txt")), sharedKeys())
        assertFalse(projects.exists())

        store.update(project) { it.put("lastOpened", 1) }
        store.moved(dir.resolve("out/b.txt"), dir.resolve("out/c.txt"), project)

        assertEquals(listOf(absolute("out/c.txt")), sharedKeys())
        assertEquals(1, store.read(project)?.get("lastOpened")?.intValue())
        assertEquals(emptyList(), projectKeys())
        assertNull(store.fileData(dir.resolve("project/a.txt"), project))
    }

    @Test
    fun `deleting or moving where nothing is stored creates no file`() {
        store.deleted(dir.resolve("out/a.txt"), project)
        store.deleted(dir.resolve("out/a.txt"), null)
        store.moved(dir.resolve("out/a.txt"), dir.resolve("out/b.txt"), null)

        assertFalse(projects.exists())
        assertFalse(sharedFile.exists())
    }

    @Test
    fun `without a project folder everything is kept in the shared file, also when moved or deleted`() {
        store.updateFileData(dir.resolve("project/a.txt"), null) { it.put("value", "a") }
        store.updateFileData(dir.resolve("project/b.txt"), null) { it.put("value", "b") }

        store.moved(dir.resolve("project/a.txt"), dir.resolve("project/c.txt"), null)
        store.deleted(dir.resolve("project/b.txt"), null)

        assertEquals(listOf(absolute("project/c.txt")), sharedKeys())
        assertEquals("a", store.fileData(dir.resolve("project/c.txt"), null)?.get("value")?.stringValue())
    }

    @Test
    fun `an entry or a files field that is no object holds no data and is replaced by a write`() {
        projects.createDirectories()
        val projectJson = projects.resolve("p.json")
        val path = mapper.writeValueAsString(project.toString())
        projectJson.writeText("""{"version": 1, "path": $path, "files": {"a.txt": 5}}""")

        assertNull(store.fileData(dir.resolve("project/a.txt"), project))
        assertTrue(store.updateFileData(dir.resolve("project/a.txt"), project) { it.put("value", "a") })
        assertEquals("a", value("project/a.txt"))

        projectJson.writeText("""{"version": 1, "path": $path, "files": 5}""")
        sharedFile.writeText("""{"version": 1, "files": "text"}""")

        assertNull(store.fileData(dir.resolve("project/a.txt"), project))
        store.moved(dir.resolve("project/a.txt"), dir.resolve("project/b.txt"), project)
        assertTrue(store.updateFileData(dir.resolve("project/b.txt"), project) { it.put("value", "b") })
        assertTrue(store.updateFileData(dir.resolve("out/b.txt"), project) { it.put("value", "o") })
        assertEquals("b", value("project/b.txt"))
        assertEquals("o", value("out/b.txt"))
    }

    @Test
    fun `a file's data is none when its project has no file or no files field`() {
        assertNull(store.fileData(dir.resolve("project/a.txt"), project))
        assertNull(store.fileData(dir.resolve("out/a.txt"), project))
        store.update(project) { it.put("lastOpened", 1) }

        assertNull(store.fileData(dir.resolve("project/a.txt"), project))
    }

    @Test
    fun `a move into the project keeps the entry in the shared file when the project's file cannot be written`() {
        remember("project/a.txt", "a")
        store.updateFileData(dir.resolve("out/s.sh"), null) { it.put("value", "script") }
        Files.setPosixFilePermissions(projects, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            assumeTrue(!Files.isWritable(projects), "the folder is writable anyway (running as root?)")

            store.moved(dir.resolve("out/s.sh"), dir.resolve("project/s.sh"), project)
        } finally {
            Files.setPosixFilePermissions(projects, PosixFilePermissions.fromString("rwxr-xr-x"))
        }

        assertEquals(listOf(absolute("out/s.sh")), sharedKeys())
        assertEquals(listOf("a.txt"), projectKeys())
    }
}
