package dev.sluide.docker.compose

import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposeStoreTest {
    @TempDir
    lateinit var dir: Path

    private val projectFolder by lazy { dir.resolve("project").createDirectories() }
    private val file by lazy { projectFolder.resolve("compose.yaml") }
    private val data by lazy { ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList()) }
    private val store by lazy { ComposeStore(data) }

    @Test
    fun `nothing stored gives the defaults`() {
        assertEquals(ComposeOptions(), store.load(file, projectFolder))
    }

    @Test
    fun `options survive a round trip, in a project and outside`() {
        val options =
            ComposeOptions(
                detached = false,
                profiles = setOf("debug", "tools"),
                build = true,
                forceRecreate = true,
                removeOrphans = true,
                projectName = "demo",
                envFiles = listOf(".env", "prod.env"),
                variables = listOf(ComposeVariable("B", "2"), ComposeVariable("A", "x = \"y\"")),
            )

        assertTrue(store.save(file, projectFolder, options))
        assertTrue(store.save(file, null, options))

        assertEquals(options, ComposeStore(data).load(file, projectFolder))
        assertEquals(options, ComposeStore(data).load(file, null))
    }

    @Test
    fun `an empty env file list differs from none`() {
        store.save(file, projectFolder, ComposeOptions(envFiles = emptyList()))

        assertEquals(emptyList(), store.load(file, projectFolder).envFiles)
    }

    @Test
    fun `default options remove the stored field`() {
        store.save(file, projectFolder, ComposeOptions(build = true))
        store.save(file, projectFolder, ComposeOptions())

        assertEquals(ComposeOptions(), store.load(file, projectFolder))
        assertFalse(data.read(projectFolder)?.has("files") ?: false, "no empty entry is left behind")
    }

    @Test
    fun `unknown and invalid content falls back value by value`() {
        data.updateFileData(file, projectFolder) { node ->
            node.putObject(COMPOSE_FIELD).apply {
                put("detached", "yes")
                put("projectName", "Not Valid")
                putArray("profiles").add("ok").add("--bad").add(3)
                put("build", true)
                put("unknown", 1)
                putArray("variables")
                    .add("junk")
                    .add(
                        mapperNode("name", "1BAD", "value", "x"),
                    ).add(mapperNode("name", "GOOD", "value", "v"))
            }
        }

        assertEquals(
            ComposeOptions(profiles = setOf("ok"), build = true, variables = listOf(ComposeVariable("GOOD", "v"))),
            store.load(file, projectFolder),
        )
    }

    @Test
    fun `blank and NUL values of env files and variables are dropped`() {
        data.updateFileData(file, projectFolder) { node ->
            node.putObject(COMPOSE_FIELD).apply {
                putArray("envFiles").add("a.env").add(" ").add("b\u0000.env")
                putArray("variables")
                    .add(mapperNode("name", "A", "value", "x\u0000y"))
                    .add(mapperNode("name", "B", "value", "ok"))
            }
        }

        val loaded = store.load(file, projectFolder)

        assertEquals(listOf("a.env"), loaded.envFiles)
        assertEquals(listOf(ComposeVariable("B", "ok")), loaded.variables)
    }

    @Test
    fun `a compose field of the wrong type gives the defaults`() {
        data.updateFileData(file, projectFolder) { it.put(COMPOSE_FIELD, "nonsense") }

        assertEquals(ComposeOptions(), store.load(file, projectFolder))
    }

    @Test
    fun `a broken store file gives the defaults and saving does not throw`() {
        val projects = dir.resolve("projects").createDirectories()
        projects.resolve("broken.json").writeText("{ not json")
        dir.resolve("shared.json").writeText("{ not json")

        assertEquals(ComposeOptions(), store.load(file, null))
        assertFalse(store.save(file, null, ComposeOptions(build = true)), "an unreadable file is not replaced")
        assertEquals("{ not json", dir.resolve("shared.json").readText())
    }

    private fun mapperNode(vararg pairs: String) =
        tools.jackson.databind.json.JsonMapper().createObjectNode().apply {
            pairs.toList().chunked(2).forEach { (key, value) -> put(key, value) }
        }
}
