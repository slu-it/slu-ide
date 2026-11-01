package dev.sluide.docker.dockerfile

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

class DockerBuildStoreTest {
    @TempDir
    lateinit var dir: Path

    private val projectFolder by lazy { dir.resolve("project").createDirectories() }
    private val file by lazy { projectFolder.resolve("Dockerfile") }
    private val data by lazy { ProjectDataStore(dir.resolve("projects"), dir.resolve("shared.json"), emptyList()) }
    private val store by lazy { DockerBuildStore(data) }

    @Test
    fun `nothing stored gives the defaults`() {
        assertEquals(BuildOptions(), store.load(file, projectFolder))
    }

    @Test
    fun `options survive a round trip, in a project and outside`() {
        val options =
            BuildOptions(
                tags = "myapp:1.0, myapp:latest",
                history = listOf("myapp:1.0, myapp:latest", "myapp"),
                context = "..",
                target = "build",
                buildArgs = mapOf("VERSION" to "2", "GONE" to "x = \"y\""),
                noCache = true,
                pull = true,
                platform = "linux/arm64",
            )

        assertTrue(store.save(file, projectFolder, options))
        assertTrue(store.save(file, null, options))

        assertEquals(options, DockerBuildStore(data).load(file, projectFolder))
        assertEquals(options, DockerBuildStore(data).load(file, null))
    }

    @Test
    fun `default options remove the stored field, and only changed values are written`() {
        store.save(file, projectFolder, BuildOptions(pull = true))
        assertEquals("""{"pull":true}""", data.fileData(file, projectFolder)?.get(DOCKER_BUILD_FIELD).toString())

        store.save(file, projectFolder, BuildOptions())

        assertEquals(BuildOptions(), store.load(file, projectFolder))
        assertFalse(data.read(projectFolder)?.has("files") ?: false, "no empty entry is left behind")
    }

    @Test
    fun `unknown and invalid content falls back value by value`() {
        data.updateFileData(file, projectFolder) { node ->
            node.putObject(DOCKER_BUILD_FIELD).apply {
                put("tags", 3)
                put("noCache", "yes")
                put("pull", true)
                put("platform", "linux/\u0000")
                put("unknown", 1)
                putArray("history")
                    .add("a")
                    .add(7)
                    .add(" ")
                    .add("a")
                    .add("b")
                putObject("buildArgs").put("OK", "v").put("NUL", "x\u0000y").put("NUM", 1)
            }
        }

        assertEquals(
            BuildOptions(pull = true, history = listOf("a", "b"), buildArgs = mapOf("OK" to "v")),
            store.load(file, projectFolder),
        )
    }

    @Test
    fun `a stored history is cut to its length`() {
        data.updateFileData(file, projectFolder) { node ->
            node.putObject(DOCKER_BUILD_FIELD).putArray("history").apply { (1..15).forEach { add("t$it") } }
        }

        assertEquals((1..10).map { "t$it" }, store.load(file, projectFolder).history)
    }

    @Test
    fun `a field of the wrong type gives the defaults`() {
        data.updateFileData(file, projectFolder) { it.put(DOCKER_BUILD_FIELD, "nonsense") }

        assertEquals(BuildOptions(), store.load(file, projectFolder))
    }

    @Test
    fun `a broken store file gives the defaults and saving does not throw`() {
        dir.resolve("shared.json").writeText("{ not json")

        assertEquals(BuildOptions(), store.load(file, null))
        assertFalse(store.save(file, null, BuildOptions(pull = true)), "an unreadable file is not replaced")
        assertEquals("{ not json", dir.resolve("shared.json").readText())
    }
}
