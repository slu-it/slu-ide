package dev.sluide.docker.cli

import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.PortMapping
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ListingParsersTest {
    @Test
    fun `Docker's containers, one JSON object per line`() {
        val containers = parseContainers(fixture("docker/ps.jsonl")).associateBy { it.name }

        assertEquals(setOf("sluidefx-paused", "sluidefx-created", "sluidefx-once", "sluidefx-web-1"), containers.keys)
        val web = containers.getValue("sluidefx-web-1")
        assertEquals("e0d50c8aebff1cec6bd7e7d294d46111fccc94bba16bde24da0de25b4a8c3181", web.id)
        assertEquals("bash:5.2", web.image)
        assertNull(web.imageId, "docker ps does not tell the image id")
        assertEquals(ContainerState.RUNNING, web.state)
        assertEquals("Up 4 seconds (healthy)", web.status)
        assertEquals(
            listOf(
                PortMapping("127.0.0.1", 18080, 80, "tcp"),
                PortMapping("0.0.0.0", 18443, 443, "tcp"),
                PortMapping("::", 18443, 443, "tcp"),
            ),
            web.ports,
        )
        assertEquals("sluidefx", web.composeProject)
        assertEquals("web", web.composeService)
        assertEquals("/home/agent/sluidefx", web.composeWorkingDir)
        assertEquals(Instant.parse("2026-11-01T13:44:03Z"), web.createdAt)
        assertEquals(
            listOf("sluidefx-data", "d2097bae9c8cd083314999fca3448fa0f5537efe2dffb949c07d1241952c3598"),
            web.volumes,
        )
        assertEquals(listOf("sluidefx-net"), web.networks)
        assertEquals(ContainerState.PAUSED, containers.getValue("sluidefx-paused").state)
        assertEquals(ContainerState.CREATED, containers.getValue("sluidefx-created").state)
        val once = containers.getValue("sluidefx-once")
        assertEquals(ContainerState.EXITED, once.state)
        assertEquals(mapOf("sluidefx" to "1"), once.labels)
        assertEquals(emptyList(), once.ports)
    }

    @Test
    fun `Podman's containers, one array with names, ports and labels as JSON`() {
        val (web, lonely) = parseContainers(fixture("podman/ps.json"))

        assertEquals("shop_web_1", web.name)
        assertEquals("docker.io/library/nginx:1.27", web.image)
        assertEquals("3b25b682ea82b2db3cc4fd48db818be788ee3f902ac7378090cf2624ec2442df", web.imageId)
        assertEquals(ContainerState.RUNNING, web.state)
        assertEquals(
            listOf(
                PortMapping(null, 8080, 80, "tcp"),
                PortMapping("127.0.0.1", 9000, 9000, "udp"),
                PortMapping("127.0.0.1", 9001, 9001, "udp"),
            ),
            web.ports,
        )
        assertEquals("shop", web.composeProject)
        assertEquals("/home/me/shop", web.composeWorkingDir)
        assertEquals(Instant.ofEpochSecond(1700000000), web.createdAt)
        assertEquals(emptyList(), web.volumes, "Podman lists only mount targets")
        assertEquals(listOf("shop_default"), web.networks)
        assertEquals(ContainerState.EXITED, lonely.state)
        assertEquals(emptyMap(), lonely.labels)
        assertEquals(emptyList(), lonely.ports)
        assertEquals(emptyList(), lonely.networks)
    }

    @ParameterizedTest
    @CsvSource(
        "'9000/tcp', '|-|9000|tcp'",
        "'0.0.0.0:8080->80/tcp', '0.0.0.0|8080|80|tcp'",
        "'[::]:8080->80/tcp', '::|8080|80|tcp'",
        "':::53->53/udp', '::|53|53|udp'",
        "'0.0.0.0:7000-7001->7000-7001/tcp', '0.0.0.0|7000|7000|tcp,0.0.0.0|7001|7001|tcp'",
        "'0.0.0.0:32768->80/tcp, 443/tcp', '0.0.0.0|32768|80|tcp,|-|443|tcp'",
        "'garbage', ''",
    )
    fun `Docker's port texts`(
        text: String,
        expected: String,
    ) {
        val ports =
            parsePorts(text).joinToString(",") {
                "${it.hostIp ?: ""}|${it.hostPort ?: "-"}|${it.containerPort}|${it.protocol}"
            }

        assertEquals(expected, ports)
    }

    @Test
    fun `Docker's images, one per tag, the dangling one without repository and tag from its own listing`() {
        val images =
            mergeImages(
                parseImages(fixture("docker/images.jsonl")),
                parseImages(fixture("docker/images-dangling.jsonl")),
            )

        val app = images.filter { it.repository == "sluidefx/app" }
        assertEquals(listOf("1", "latest"), app.map { it.tag })
        assertEquals(1, app.map { it.id }.distinct().size)
        assertEquals(25_400_000, app.first().size)
        assertEquals(Instant.parse("2026-11-01T13:44:41Z"), app.first().createdAt)
        assertEquals("sluidefx/app:1", app.first().reference)
        assertFalse(app.first().dangling)
        val dangling = images.single { it.dangling }
        assertEquals("sha256:4afe37744622e8281a79b0d2cf403ab748b32653d540c1924c58c5670968a0e1", dangling.id)
        assertNull(dangling.repository)
        assertNull(dangling.tag)
        assertNull(dangling.digest)
        assertEquals(dangling.id, dangling.reference)
        assertEquals(images, mergeImages(images, images.filter { it.dangling }), "each image once")
        val debian = images.single { it.repository == "debian" }
        assertEquals("stable", debian.tag)
        assertEquals("sha256:f7c6c9e4aba843dc41bac68bbf104773f71fabf53f74f8441049aaa56e50c4e2", debian.digest)
    }

    @Test
    fun `Podman's images, an entry per repository tag, the dangling one once`() {
        val images = parseImages(fixture("podman/images.json"))

        assertEquals(
            listOf("docker.io/library/nginx:1.27", "localhost:5000/team/nginx:latest"),
            images.filter { !it.dangling }.map { it.reference },
        )
        assertEquals(
            listOf("localhost:5000/team/nginx" to "latest"),
            images.filter { it.tag == "latest" }.map {
                it.repository to
                    it.tag
            },
        )
        assertEquals(191_720_123, images.first().size)
        assertEquals(Instant.ofEpochSecond(1699000000), images.first().createdAt)
        val dangling = images.single { it.dangling }
        assertNull(dangling.repository)
        assertEquals(7_800_000, dangling.size)
    }

    @Test
    fun `volumes of both, with anonymous ones recognised and use from the dangling list`() {
        val docker =
            parseVolumes(
                fixture("docker/volumes.jsonl"),
                dangling = setOf("sluidefx-data"),
            ).associateBy { it.name }
        val anonymous = docker.getValue("d2097bae9c8cd083314999fca3448fa0f5537efe2dffb949c07d1241952c3598")
        assertTrue(anonymous.anonymous)
        assertEquals(true, anonymous.inUse)
        val data = docker.getValue("sluidefx-data")
        assertFalse(data.anonymous)
        assertEquals(false, data.inUse)
        assertEquals(mapOf("sluidefx" to "1"), data.labels)
        assertEquals("/var/lib/docker/volumes/sluidefx-data/_data", data.mountpoint)

        val podman = parseVolumes(fixture("podman/volumes.json"), dangling = null)
        assertEquals(listOf(false, true), podman.map { it.anonymous })
        assertEquals(listOf(null, null), podman.map { it.inUse })
        assertEquals(mapOf("com.docker.compose.project" to "shop"), podman.first().labels)
    }

    @Test
    fun `networks of both, the engine's own marked builtin`() {
        val docker = parseNetworks(fixture("docker/networks.jsonl"))
        assertEquals(listOf("bridge", "host", "none", "sluidefx-net"), docker.map { it.name })
        assertEquals(listOf(true, true, true, false), docker.map { it.builtin })
        assertEquals("null", docker[2].driver)
        assertEquals("local", docker[3].scope)
        assertEquals("ad2d8dfd26ff1c04e49819f54f62f226240baabb39bd8c28642281acf8f476d3", docker[3].id)

        val podman = parseNetworks(fixture("podman/networks.json"))
        assertEquals(listOf("podman", "shop_default"), podman.map { it.name })
        assertEquals(listOf(true, false), podman.map { it.builtin })
        assertEquals("bridge", podman[1].driver)
        assertEquals("local", podman[1].scope)
        assertEquals(mapOf("com.docker.compose.project" to "shop"), podman[1].labels)
        assertEquals(Instant.parse("2023-11-14T21:13:19.5Z"), podman[1].createdAt)
    }

    @Test
    fun `Docker's label text keeps commas inside values`() {
        val labels = dockerJson.readTree("""{"Labels":"a=1,b=x,y,c="}""").labels("Labels")

        assertEquals(mapOf("a" to "1", "b" to "x,y", "c" to ""), labels)
    }

    @Test
    fun `objects or arrays where a text, number or truth value is expected are left out, not failures`() {
        val containers =
            parseContainers(
                """{"ID":"x","Names":{"a":1},"Image":["b"],"State":{"s":1},"Ports":{"p":1},"Labels":[1],""" +
                    """"Created":{"c":1},"Mounts":{"m":1},"Networks":[{"n":1},"net"]}""",
            )
        val images =
            parseImages("""{"Id":"y","Size":{"a":1},"Dangling":{"d":1},"Created":[1],"RepoTags":[{"r":1},"app:1"]}""")
        val volumes = parseVolumes("""{"Name":"v","Labels":{"k":{"nested":1}},"Anonymous":[true]}""", null)
        val details =
            parseContainerDetails(
                """[{"Id":"z","State":{"Status":"exited","ExitCode":{"x":1}},"RestartCount":[1],""" +
                    """"Config":{"Cmd":[{"c":1},"sh"],"Entrypoint":{"e":1},"Env":{"E":1}},""" +
                    """"Mounts":[{"Destination":"/d","RW":{}}]}]""",
            )

        assertEquals(listOf("x"), containers.map { it.id })
        assertEquals(listOf("x" to listOf("net")), containers.map { it.name to it.networks })
        assertEquals(emptyList(), containers.single().ports)
        assertEquals(listOf("app:1"), images.map { it.reference })
        assertEquals(0, images.single().size)
        assertEquals(mapOf("k" to """{"nested":1}"""), volumes.single().labels)
        assertEquals(false, volumes.single().anonymous)
        assertEquals(listOf("sh"), details.command)
        assertEquals(emptyList(), details.entrypoint)
        assertEquals(null, details.exitCode)
        assertEquals(true, details.mounts.single().readWrite)
    }
}
