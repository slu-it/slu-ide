package dev.sluide.docker.run

import dev.sluide.projectdata.ProjectDataStore
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
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

    @ParameterizedTest
    @ValueSource(
        strings = [
            """"name":5""",
            """"name":"-bad"""",
            """"name":"a\u0000b"""",
            """"ports":"80"""",
            """"ports":["80\u0000",7,"x"]""",
            """"env":"A=1"""",
            """"env":[{"name":"A"},{"value":"v"},{"name":"B","value":5},{"name":"C","value":"x\u0000"},{"name":"1"}]""",
            """"volumes":{"source":"/a","target":"/b"}""",
            """"volumes":[{"source":"/a"},{"target":"/a"},{"source":5,"target":"/a"},{"source":"/a","target":"/b\u0000"}]""",
            """"network":"-host"""",
            """"network":"  """",
            """"network":false""",
            """"command":5""",
            """"command":"a\u0000b"""",
            """"detached":"x"""",
            """"rm":"x"""",
        ],
    )
    fun `a stored value of the wrong shape falls back alone, the rest is kept`(invalid: String) {
        // The companion is valid and not what the invalid entry is about; losing it shows a fallback to all defaults.
        val aboutName = invalid.startsWith("\"name\"")
        val companion = if (aboutName) """"network":"shop"""" else """"name":"web""""
        val expected = if (aboutName) RunOptions("nginx:1", network = "shop") else RunOptions("nginx:1", name = "web")
        shared.writeText("""{"version":1,"dockerRuns":{"nginx":{$invalid,$companion}}}""")

        assertEquals(expected, store.load("nginx:1", null))
    }

    @Test
    fun `stored booleans of the defaults are read and a read-only flag of the wrong type is off`() {
        shared.writeText(
            """
            {"version":1,"dockerRuns":{"nginx":{"detached":true,"rm":false,
            "volumes":[{"source":"/a","target":"/b","readOnly":"yes"},{"source":"/c","target":"/d","readOnly":true}]}}}
            """.trimIndent(),
        )

        val loaded = store.load("nginx:1", null)

        assertTrue(loaded.detached)
        assertFalse(loaded.removeWhenStopped)
        assertEquals(listOf(RunVolume("/a", "/b", false), RunVolume("/c", "/d", true)), loaded.volumes)
    }

    @Test
    fun `only values that differ from the defaults are written`() {
        assertTrue(write(RunOptions("nginx:1")).isEmpty)
        assertEquals("""{"detached":false}""", write(RunOptions("nginx:1", detached = false)).toString())
        assertEquals("""{"rm":true}""", write(RunOptions("nginx:1", removeWhenStopped = true)).toString())
    }

    @Test
    fun `the values of other repositories stay, and a stored field of the wrong shape is replaced`() {
        assertTrue(store.save(RunOptions("nginx:1", name = "web"), null))
        assertTrue(store.save(RunOptions("redis:7", name = "cache"), null))
        assertEquals("web", DockerRunStore(data).load("nginx:1", null).name)
        assertEquals("cache", DockerRunStore(data).load("redis:7", null).name)

        shared.writeText("""{"version":1,"dockerRuns":"x"}""")
        assertTrue(DockerRunStore(data).save(RunOptions("nginx:1", name = "web"), null))
        assertEquals("web", DockerRunStore(data).load("nginx:1", null).name)
    }

    @Test
    fun `saving into a store file that cannot be read fails and leaves it as it is`() {
        shared.writeText("{not json")

        assertFalse(store.save(used, null))
        assertEquals("{not json", shared.readText())
    }

    @Test
    fun `an unreadable or wrongly shaped store gives the defaults`() {
        shared.writeText("{not json")
        assertEquals(RunOptions("nginx"), store.load("nginx", null))

        shared.writeText("""{"version":1,"dockerRuns":["x"]}""")
        assertEquals(RunOptions("nginx"), DockerRunStore(data).load("nginx", null))
    }
}
