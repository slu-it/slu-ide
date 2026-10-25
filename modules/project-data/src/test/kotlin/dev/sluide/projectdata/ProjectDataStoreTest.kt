package dev.sluide.projectdata

import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectDataStoreTest {
    @TempDir
    lateinit var dir: Path

    private val projects by lazy { dir.resolve("projects") }
    private val project by lazy { dir.resolve("project").createDirectories() }

    private fun store() = ProjectDataStore(projects, dir.resolve("outside-projects.json"), emptyList())

    private fun jsonFiles() = projects.listDirectoryEntries("*.json")

    private fun json(file: Path) = (readJson(file) as JsonRead.Found).node

    private fun projectFile(
        name: String,
        content: String,
    ): Path = projects.createDirectories().resolve(name).apply { writeText(content) }

    @Test
    fun `update creates one file named by a UUID with the version and the path`() {
        assertTrue(store().update(project) { it.put("lastOpened", 5) })

        val file = jsonFiles().single()
        UUID.fromString(file.name.removeSuffix(".json"))
        val node = json(file)
        assertEquals(1, node.get("version").intValue())
        assertEquals(project.toString(), node.get("path").stringValue())
        assertEquals(5, node.get("lastOpened").intValue())
    }

    @Test
    fun `a new store finds the project by scanning, its folder compared normalized`() {
        store().update(project.resolve("sub/..")) { it.put("lastOpened", 5) }

        val store = store()
        assertEquals(5, store.read(project)?.get("lastOpened")?.intValue())
        assertEquals(listOf(project), store.projects().map { it.folder })
        assertEquals(
            5,
            store
                .projects()
                .single()
                .data
                .get("lastOpened")
                .intValue(),
        )
        assertNull(store.read(dir))
    }

    @Test
    fun `a second update changes the same file`() {
        store().update(project) { it.put("lastOpened", 5) }
        store().update(project) { it.put("lastOpened", 6) }

        assertEquals(6, json(jsonFiles().single()).get("lastOpened").intValue())
    }

    @Test
    fun `unknown fields survive updates, at the top and per file`() {
        val file =
            projectFile(
                "${UUID.randomUUID()}.json",
                """
                {"version": 1, "path": ${quoted(project)}, "other": {"x": 1},
                 "files": {"a.txt": {"mine": true, "runArguments": ["x"]}}}
                """.trimIndent(),
            )

        store().update(project) { it.put("lastOpened", 1) }
        store().updateFileData(project.resolve("a.txt"), project) { it.putArray("runArguments").add("y") }

        val node = json(file)
        assertEquals(1, node.get("other").get("x").intValue())
        assertEquals(1, node.get("lastOpened").intValue())
        val data = node.get("files").get("a.txt")
        assertTrue(data.get("mine").booleanValue())
        assertEquals("y", data.get("runArguments").single().stringValue())
    }

    @ParameterizedTest
    @ValueSource(strings = ["{\"version\": 1, \"path\": ", "{\"version\": 1}", "[]"])
    fun `an unreadable file is skipped and never replaced or deleted, and blocks no other project`(content: String) {
        val broken = projectFile("broken.json", content)
        val other = dir.resolve("other").createDirectories()

        val store = store()
        assertTrue(store.update(project) { it.put("lastOpened", 2) })
        assertTrue(store.updateFileData(other.resolve("a.txt"), other) { it.put("x", 1) })

        assertEquals(listOf(other, project), store.projects().map { it.folder })
        assertEquals(3, jsonFiles().size)
        store.remove(project)
        assertEquals(content, broken.readText())
        assertEquals(listOf(other), store.projects().map { it.folder })
    }

    @Test
    fun `a file of a newer version is read but never written`() {
        val content = """{"version": 2, "path": ${quoted(project)}, "lastOpened": 7}"""
        val file = projectFile("${UUID.randomUUID()}.json", content)

        val store = store()
        assertEquals(7, store.read(project)?.get("lastOpened")?.intValue())
        assertFalse(store.update(project) { it.put("lastOpened", 8) })
        assertFalse(store.updateFileData(project.resolve("a.txt"), project) { it.put("x", 1) })

        assertEquals(content, file.readText())
        assertEquals(1, jsonFiles().size)
    }

    @Test
    fun `remove deletes the project's file`() {
        val other = dir.resolve("other").createDirectories()
        store().update(project) { it.put("lastOpened", 1) }
        store().update(other) { it.put("lastOpened", 2) }

        store().remove(project.resolve("."))

        assertNull(store().read(project))
        assertEquals(listOf(other), store().projects().map { it.folder })
    }

    @Test
    fun `of two files for one folder the newest wins, and remove deletes both`() {
        val older = projectFile("${UUID.randomUUID()}.json", """{"version": 1, "path": ${quoted(project)}, "n": 1}""")
        val newer = projectFile("${UUID.randomUUID()}.json", """{"version": 1, "path": ${quoted(project)}, "n": 2}""")
        Files.setLastModifiedTime(older, FileTime.fromMillis(1_000_000))
        Files.setLastModifiedTime(newer, FileTime.fromMillis(2_000_000))

        val store = store()
        assertEquals(2, store.read(project)?.get("n")?.intValue())
        assertEquals(1, store.projects().size)
        store.update(project) { it.put("m", 3) }
        assertEquals(3, json(newer).get("m")?.intValue())
        assertNull(json(older).get("m"))

        store.remove(project)

        assertEquals(emptyList(), jsonFiles())
    }

    @Test
    fun `temporary and other files in the folder are ignored`() {
        val content = """{"version": 1, "path": ${quoted(project)}, "n": 1}"""
        val temporary = projectFile("x.json123.tmp", content)
        projectFile("notes.txt", content)

        val store = store()
        assertEquals(emptyList(), store.projects())
        assertTrue(store.update(project) { it.put("n", 2) })

        assertEquals(1, jsonFiles().size)
        assertEquals(content, temporary.readText())
    }

    @Test
    fun `constructing the store touches no file`() {
        val legacy = dir.resolve("projects.properties").apply { writeText("a=1") }
        var migrations = 0
        val migration =
            object : LegacyProjectData {
                override val file = legacy

                override fun migrate(
                    entries: Map<String, String>,
                    store: ProjectDataStore,
                ): Boolean {
                    migrations++
                    return true
                }
            }

        ProjectDataStore(projects, dir.resolve("outside-projects.json"), listOf(migration))

        assertFalse(projects.exists())
        assertEquals(0, migrations)
        assertTrue(legacy.exists())
    }

    @Test
    fun `Spring creates the store when no module has a migration`() {
        AnnotationConfigApplicationContext().use { context ->
            context.register(ProjectDataStore::class.java)
            context.refresh()

            assertNotNull(context.getBean(ProjectDataStore::class.java))
        }
    }

    private fun quoted(path: Path) = mapper.writeValueAsString(path.toString())
}
