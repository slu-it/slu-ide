package dev.sluide.docker.run

import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DockerRunStoreTest {
    @TempDir
    lateinit var dir: Path

    private val projectFolder by lazy { dir.resolve("project").createDirectories() }
    private val shared by lazy { dir.resolve("shared.json") }
    private val data by lazy { ProjectDataStore(dir.resolve("projects"), shared, emptyList()) }
    private val store by lazy { DockerRunStore(data) }

    private val used =
        RunOptions(
            image = "nginx:1",
            name = "web",
            ports = listOf("8080:80"),
            environment = listOf(RunEnvVar("A", "x = \"y\"")),
            volumes = listOf(RunVolume("./html", "/usr/share/nginx/html", readOnly = true)),
            network = "shop",
            command = "nginx -g 'daemon off;'",
            detached = false,
            removeWhenStopped = true,
        )

    @Test
    fun `nothing stored gives the defaults with the asked reference`() {
        assertEquals(RunOptions("nginx:1"), store.load("nginx:1", projectFolder))
        assertEquals(RunOptions("nginx:1"), store.load("nginx:1", null))
    }

    @Test
    fun `values survive a round trip in a project and in the shared file, for every tag`() {
        assertTrue(store.save(used, projectFolder))
        assertTrue(store.save(used, null))

        assertEquals(used.copy(image = "nginx:2"), DockerRunStore(data).load("nginx:2", projectFolder))
        assertEquals(used, DockerRunStore(data).load("nginx:1", null))
        assertNull(data.read(projectFolder)?.get("dockerRuns")?.get("other"), "keyed by repository")
    }

    @Test
    fun `a registry port does not make a tag`() {
        store.save(used.copy(image = "reg:5000/app:1.0"), projectFolder)

        assertEquals("shop", store.load("reg:5000/app:2.0", projectFolder).network)
        assertEquals(RunOptions("reg:5000/other"), store.load("reg:5000/other", projectFolder))
    }

    @Test
    fun `an untagged image is not remembered`() {
        val id = "0123456789ab"

        assertFalse(store.save(used.copy(image = id), projectFolder))

        assertNull(data.read(projectFolder)?.get("dockerRuns"))
        assertEquals(RunOptions(id), store.load(id, projectFolder))
    }

    @Test
    fun `default values remove the entry and the field`() {
        store.save(used, projectFolder)
        store.save(RunOptions("nginx:1"), projectFolder)

        assertNull(data.read(projectFolder)?.get("dockerRuns"))
    }

    @Test
    fun `variable names are stored trimmed`() {
        store.save(RunOptions("nginx:1", environment = listOf(RunEnvVar(" A ", "x"))), projectFolder)

        assertEquals(listOf(RunEnvVar("A", "x")), store.load("nginx:1", projectFolder).environment)
    }

    @Test
    fun `blank rows are not stored`() {
        store.save(RunOptions("nginx:1", ports = listOf(""), environment = listOf(RunEnvVar("", ""))), projectFolder)

        assertNull(data.read(projectFolder)?.get("dockerRuns"))
    }

    @Test
    fun `broken stored values are dropped one by one and never throw`() {
        shared.writeText(
            """
            {"version":1,"dockerRuns":{"nginx":{"name":"-bad","ports":["80","x",5],"env":[{"name":"1A","value":"v"},
            {"name":"OK","value":"v"},"junk"],"volumes":[{"source":"/a","target":"rel"},{"source":"/a","target":"/b"}],
            "network":7,"command":"echo 'open","detached":"yes","rm":true}}}
            """.trimIndent(),
        )

        assertEquals(
            RunOptions(
                "nginx:1",
                ports = listOf("80"),
                environment = listOf(RunEnvVar("OK", "v")),
                volumes = listOf(RunVolume("/a", "/b")),
                removeWhenStopped = true,
            ),
            store.load("nginx:1", null),
        )
    }

    @Test
    fun `an unreadable or wrongly shaped store gives the defaults`() {
        shared.writeText("{not json")
        assertEquals(RunOptions("nginx"), store.load("nginx", null))

        shared.writeText("""{"version":1,"dockerRuns":["x"]}""")
        assertEquals(RunOptions("nginx"), DockerRunStore(data).load("nginx", null))
    }
}
