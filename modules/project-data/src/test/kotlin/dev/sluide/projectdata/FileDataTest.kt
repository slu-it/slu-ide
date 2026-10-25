package dev.sluide.projectdata

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Path
import kotlin.io.path.createDirectories
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
    private val outsideFile by lazy { dir.resolve("outside-projects.json") }
    private val project by lazy { dir.resolve("project").createDirectories() }

    private val store by lazy { ProjectDataStore(projects, outsideFile, emptyList()) }

    /** The keys of the per-file entries in the project's file. */
    private fun projectKeys(): List<String> = store.read(project)?.let(::keys) ?: emptyList()

    /** The keys of the per-file entries in the outside file. */
    private fun outsideKeys(): List<String> =
        (readJson(outsideFile) as? JsonRead.Found)?.node?.let(::keys) ?: emptyList()

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
    fun `a file's data lives in its project's file, else in the outside file`(
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
            assertEquals(emptyList(), outsideKeys())
        } else {
            assertEquals(emptyList(), projectKeys())
            assertEquals(listOf(absolute(file)), outsideKeys())
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
            assertEquals(emptyList(), outsideKeys())
        } else {
            assertEquals(emptyList(), projectKeys())
            assertEquals(listOf(absolute("outside/d/s.sh")), outsideKeys())
        }
    }

    @Test
    fun `a move of something without data changes nothing`() {
        remember("project/a.txt", "a")

        store.moved(dir.resolve("project/b.txt"), dir.resolve("project/c.txt"), project)

        assertEquals(listOf("a.txt"), projectKeys())
        assertFalse(outsideFile.toFile().exists())
    }

    @Test
    fun `a move drops the data stored at the target and under it in both files, also without data to move`() {
        remember("project/t", "stale")
        remember("project/t/u", "stale")
        remember("project/tu", "kept")
        store.updateFileData(dir.resolve("project/t/v"), null) { it.put("value", "stale") }

        store.moved(dir.resolve("project/a.txt"), dir.resolve("project/t"), project)

        assertEquals(listOf("tu"), projectKeys())
        assertEquals(emptyList(), outsideKeys())
    }

    @Test
    fun `a move out of the project keeps the entry in the project's file when the outside file cannot be written`() {
        remember("project/s.sh", "script")
        outsideFile.writeText("{broken")

        store.moved(dir.resolve("project/s.sh"), dir.resolve("out/s.sh"), project)

        assertEquals(listOf("s.sh"), projectKeys())
        assertEquals("{broken", outsideFile.readText())
    }

    @Test
    fun `a moved project folder takes its entries to the outside file`() {
        remember("project/a.txt", "a")

        store.moved(project, dir.resolve("moved"), project)

        assertEquals(emptyList(), projectKeys())
        assertEquals(listOf(absolute("moved/a.txt")), outsideKeys())
    }

    @ParameterizedTest
    @ValueSource(strings = ["{broken", "[]"])
    fun `an unreadable outside file is never replaced`(content: String) {
        outsideFile.writeText(content)
        val file = dir.resolve("out/a.txt")

        assertFalse(store.updateFileData(file, project) { it.put("value", "v") })
        store.moved(dir.resolve("out/b.txt"), file, project)
        store.deleted(file, project)

        assertNull(store.fileData(file, project))
        assertEquals(content, outsideFile.readText())
    }

    @Test
    fun `an outside file of a newer version is read but never written`() {
        val file = dir.resolve("out/a.txt")
        val content = """{"version": 2, "files": {${mapper.writeValueAsString(
            absolute("out/a.txt"),
        )}: {"value": "v"}}}"""
        outsideFile.writeText(content)

        assertEquals("v", value("out/a.txt"))
        assertFalse(store.updateFileData(file, project) { it.put("value", "w") })
        store.deleted(file, project)

        assertEquals(content, outsideFile.readText())
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
        assertEquals(outsideRemaining.split(";").map(::absolute).toSet(), outsideKeys().toSet())
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
        assertNull((readJson(outsideFile) as JsonRead.Found).node.get("files"))
    }
}
