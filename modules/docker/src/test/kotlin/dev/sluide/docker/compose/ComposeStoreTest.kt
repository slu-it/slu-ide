package dev.sluide.docker.compose

import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.node.ObjectNode
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
        assertTrue(data.fileData(file, projectFolder)?.isEmpty ?: true, "no empty entry is left behind")
        assertTrue(write(ComposeOptions()).isEmpty, "defaults write nothing, a detached default included")
        assertEquals("""{"build":true}""", write(ComposeOptions(build = true)).toString())
        assertEquals("""{"detached":false}""", write(ComposeOptions(detached = false)).toString())
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

    @Test
    fun `a stored true of detached, the default, is read as true`() {
        assertTrue(readJson("""{"detached":true}""").detached)
        assertFalse(readJson("""{"detached":false}""").detached)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """"build":"yes"""",
            """"forceRecreate":1""",
            """"removeOrphans":"true"""",
            """"detached":"no"""",
            """"projectName":5""",
            """"projectName":null""",
            """"envFiles":"a.env"""",
            """"variables":"A=1"""",
            """"profiles":"debug"""",
            """"variables":["junk",{"name":5,"value":"v"},{"name":"A","value":5},{"name":"A"},{"value":"v"}]""",
            """"variables":[{"name":"A","value":"x\u0000"},{"name":"a b","value":"x"}]""",
        ],
    )
    fun `a value of the wrong type falls back alone, the rest is kept`(invalid: String) {
        // Only one companion that the invalid entry is not about, so that a lost companion shows the fallback.
        val companion = if (invalid.startsWith("\"profiles\"")) """"build":true""" else """"profiles":["ok"]"""
        val expected =
            if (invalid.startsWith(
                    "\"profiles\"",
                )
            ) {
                ComposeOptions(build = true)
            } else {
                ComposeOptions(profiles = setOf("ok"))
            }

        assertEquals(expected, readJson("{$invalid,$companion}"))
    }

    private fun readJson(json: String): ComposeOptions =
        read(
            tools.jackson.databind.json
                .JsonMapper()
                .readTree(json) as ObjectNode,
        )

    private fun mapperNode(vararg pairs: String) =
        tools.jackson.databind.json.JsonMapper().createObjectNode().apply {
            pairs.toList().chunked(2).forEach { (key, value) -> put(key, value) }
        }
}
