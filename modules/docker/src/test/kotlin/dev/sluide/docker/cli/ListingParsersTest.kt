package dev.sluide.docker.cli

import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.Image
import dev.sluide.docker.model.Network
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

    @Test
    fun `a container needs an id, its name is the first of Names without a slash, else the short id`() {
        val id = "e0d50c8aebff1cec6bd7e7d294d46111fccc94bba16bde24da0de25b4a8c3181"
        val containers =
            parseContainers(
                """{"Names":"orphan"}""" + "\n" +
                    """{"ID":"$id"}""" + "\n" +
                    """{"ID":"b","Names":"web-1,other/alias"}""" + "\n" +
                    """{"ID":"c","Names":"/inspect-style"}""" + "\n" +
                    """{"Id":"d","Names":[]}""",
            )

        assertEquals(listOf(id.take(12), "web-1", "inspect-style", "d"), containers.map { it.name })
        assertEquals("", containers.first().image)
        assertEquals("", containers.first().status)
        assertNull(containers.first().createdAt)
    }

    @ParameterizedTest
    @CsvSource(
        "'\"Created\":1700000000', 2023-11-14T22:13:20Z",
        "'\"Created\":1700000000,\"CreatedAt\":\"2020-01-01 00:00:00 +0000 UTC\"', 2023-11-14T22:13:20Z",
        "'\"Created\":0,\"CreatedAt\":\"2020-01-01 00:00:00 +0000 UTC\"', ",
        "'\"CreatedAt\":\"2026-11-01 13:44:03 +0000 UTC\"', 2026-11-01T13:44:03Z",
        "'\"Created\":\"2026-11-01T13:44:03Z\"', 2026-11-01T13:44:03Z",
        "'\"Created\":\"1700000000\"', 2023-11-14T22:13:20Z",
        "'\"CreatedAt\":\"2 minutes ago\",\"Created\":\"2026-11-01T13:44:03Z\"', 2026-11-01T13:44:03Z",
        "'\"CreatedAt\":\"2 minutes ago\"', ",
        "'\"x\":1', ",
    )
    fun `the creation time is Podman's seconds, Docker's CreatedAt or a Created text`(
        fields: String,
        expected: String?,
    ) {
        val created = parseContainers("""{"ID":"x",$fields}""").single().createdAt

        assertEquals(expected?.let { Instant.parse(it) }, created)
    }

    @Test
    fun `Podman's port objects, with and without a range, a host address or a protocol`() {
        val web =
            parseContainers(
                """{"ID":"x","Ports":[{"container_port":80},{"host_port":1},""" +
                    """{"host_ip":"127.0.0.1","container_port":9000,"host_port":9000,"range":2,"protocol":"udp"},""" +
                    """{"container_port":53,"host_port":0,"range":0},{"container_port":7,"host_port":1,"range":-4},""" +
                    """{"containerPort":8,"hostPort":9,"Type":"sctp"}]}""",
            ).single()

        assertEquals(
            listOf(
                PortMapping(null, null, 80, "tcp"),
                PortMapping("127.0.0.1", 9000, 9000, "udp"),
                PortMapping("127.0.0.1", 9001, 9001, "udp"),
                PortMapping(null, null, 53, "tcp"),
                PortMapping(null, 1, 7, "tcp"),
                PortMapping(null, 9, 8, "sctp"),
            ),
            web.ports,
        )
    }

    @Test
    fun `a number outside the range of a long leaves its field empty, the rest of the listing stays`() {
        val huge = "123456789012345678901234567890"
        val containers =
            parseContainers(
                """{"ID":"a","Created":1e30}""" + "\n" +
                    """{"ID":"b","Created":1700000000,"Ports":[{"container_port":$huge,"host_port":1e30},""" +
                    """{"container_port":80,"host_port":$huge,"range":$huge}]}""",
            )
        val images = parseImages("""{"Id":"i","Size":1e30,"Created":$huge,"RepoTags":["a:1"]}""")
        val details =
            parseContainerDetails("""[{"Id":"d","RestartCount":1e30,"State":{"Status":"exited","ExitCode":$huge}}]""")

        assertEquals(listOf("a", "b"), containers.map { it.name })
        assertNull(containers[0].createdAt)
        assertEquals(Instant.ofEpochSecond(1700000000), containers[1].createdAt)
        assertEquals(listOf(PortMapping(null, null, 80, "tcp")), containers[1].ports)
        assertEquals(listOf(0L to null), images.map { it.size to it.createdAt })
        assertEquals(0 to null, details.restartCount to details.exitCode)
    }

    @Test
    fun `Unix seconds beyond what an Instant holds leave the time empty, the listing stays`() {
        val containers =
            parseContainers(
                """{"ID":"a","Created":9000000000000000000}""" + "\n" +
                    """{"ID":"b","Created":"9000000000000000000"}""" + "\n" +
                    """{"ID":"c","Created":1700000000}""",
            )

        assertEquals(listOf("a", "b", "c"), containers.map { it.name })
        assertEquals(listOf(null, null, Instant.ofEpochSecond(1700000000)), containers.map { it.createdAt })
    }

    @Test
    fun `a number with a fraction or an exponent is cut to its whole part, not dropped`() {
        val container = parseContainers("""{"ID":"a","Created":1700000000.5}""").single()
        val images =
            parseImages(
                """{"Id":"i","Size":1234.5,"RepoTags":["a:1"]}""" + "\n" +
                    """{"Id":"j","Size":1e10,"RepoTags":["a:2"]}""",
            )

        assertEquals(Instant.ofEpochSecond(1700000000), container.createdAt)
        assertEquals(listOf(1234L, 10_000_000_000L), images.map { it.size })
    }

    @Test
    fun `a port too large for an int is dropped, the other ports and containers stay`() {
        val containers =
            parseContainers(
                """{"ID":"a","Ports":"99999999999/tcp, 80/tcp"}""" + "\n" + """{"ID":"b","Ports":"9000/tcp"}""",
            )

        assertEquals(listOf("a", "b"), containers.map { it.name })
        assertEquals(listOf(PortMapping(null, null, 80, "tcp")), containers[0].ports)
        assertEquals(listOf(null), parsePorts("0.0.0.0:99999999999->80/tcp").map { it.hostPort }, "unpublished")
    }

    @Test
    fun `a range is cut at the limit`() {
        val ports = parsePorts("0.0.0.0:1000-9000->1000-9000/tcp")

        assertEquals(1024, ports.size)
        assertEquals(1000 to 1000, ports.first().hostPort to ports.first().containerPort)
        assertEquals(2023 to 2023, ports.last().hostPort to ports.last().containerPort)
    }

    @Test
    fun `a port without an address has none, a bracketed address loses its brackets`() {
        assertNull(parsePorts("9000/tcp").single().hostIp)
        assertNull(parsePorts(":8080->80/tcp").single().hostIp)
        assertEquals("::1", parsePorts("[::1]:8080->80/tcp").single().hostIp)
    }

    @Test
    fun `a single host port with a container range stays the same host port`() {
        // Hostile shape: Docker prints equal ranges on both sides; the parser must not invent host ports from it.
        assertEquals(listOf(8000, 8000), parsePorts("1.2.3.4:8000->7000-7001/tcp").map { it.hostPort })
        assertEquals(listOf(7000, 7001), parsePorts("1.2.3.4:7000-7001->7000-7001/tcp").map { it.hostPort })
    }

    private fun image(
        id: String,
        repository: String?,
        tag: String?,
    ) = Image(id, repository, tag, null, 1, null, false)

    @Test
    fun `merging images keeps one per id, repository and tag`() {
        val a = image("1", "app", "latest")
        val merged =
            mergeImages(
                listOf(a, image("1", "app", "v1"), image("1", "other", "latest"), image("2", "app", "latest")),
                listOf(a.copy(size = 9), image("3", null, null), image("4", null, null)),
            )

        assertEquals(
            listOf("1/app/latest", "1/app/v1", "1/other/latest", "2/app/latest", "3/null/null", "4/null/null"),
            merged.map { "${it.id}/${it.repository}/${it.tag}" },
        )
        assertEquals(1, merged.first().size, "the listed one wins")
    }

    @Test
    fun `an image needs an id, a missing or unreadable size is 0`() {
        val images =
            parseImages(
                """{"Repository":"a","Tag":"1"}""" + "\n" +
                    """{"ID":"x","Repository":"a","Tag":"1"}""" + "\n" +
                    """{"ID":"y","Repository":"a","Tag":"2","Size":"N/A"}""" + "\n" +
                    """{"ID":"z","Repository":"a","Tag":"3","Size":"25.4MB"}""" + "\n" +
                    """{"Id":"w","RepoTags":["a:4"],"Size":191720123}""",
            )

        assertEquals(listOf("x", "y", "z", "w"), images.map { it.id })
        assertEquals(listOf(0L, 0L, 25_400_000L, 191_720_123L), images.map { it.size })
    }

    @Test
    fun `docker's none placeholders are no repository, tag or digest`() {
        fun one(fields: String) = parseImages("""{"ID":"x",$fields}""").single()

        val untagged = one(""""Repository":"<none>","Tag":"<none>","Digest":"<none>"""")
        assertEquals(Triple(null, null, null), Triple(untagged.repository, untagged.tag, untagged.digest))
        assertTrue(untagged.dangling)
        val byDigest = one(""""Repository":"alpine","Tag":"<none>","Digest":"sha256:ab"""")
        assertEquals(Triple("alpine", null, "sha256:ab"), Triple(byDigest.repository, byDigest.tag, byDigest.digest))
        assertFalse(byDigest.dangling, "it has a repository")
        val onlyTag = one(""""Repository":"<none>","Tag":"x"""")
        assertEquals(null to "x", onlyTag.repository to onlyTag.tag)
        assertFalse(onlyTag.dangling)
    }

    @Test
    fun `repository tags come from RepoTags unless the object has only a Repository`() {
        fun tags(fields: String) = parseImages("""{"ID":"x",$fields}""").map { it.repository to it.tag }

        assertEquals(listOf("a" to "1", "b" to "2"), tags(""""RepoTags":["a:1","b:2"]"""))
        assertEquals(listOf("a" to "1"), tags(""""RepoTags":["a:1"],"Repository":"ignored","Tag":"t""""))
        assertEquals(listOf(null to null), tags(""""RepoTags":["<none>:<none>"]"""))
        assertEquals(listOf(null to null), tags(""""RepoTags":[]"""))
        assertEquals(listOf(null to null), tags(""""Tag":"t""""), "neither RepoTags nor Repository: untagged")
        assertEquals(listOf("a" to "1"), tags(""""Repository":"a","Tag":"1""""))
        assertEquals(
            listOf("localhost:5000/team/app" to "v1", "nginx" to null, "a:b/c" to null, "x" to ""),
            tags(""""RepoTags":["localhost:5000/team/app:v1","nginx","a:b/c","x:"]"""),
        )
    }

    @Test
    fun `Podman's Dangling flag decides over the tags, both ways`() {
        fun dangling(fields: String) = parseImages("""{"ID":"x",$fields}""").single().dangling

        assertTrue(dangling(""""Dangling":true,"RepoTags":["a:1"]"""))
        assertFalse(dangling(""""Dangling":false"""))
        assertFalse(dangling(""""Dangling":"false""""))
        assertTrue(dangling(""""Size":1"""))
        assertFalse(dangling(""""RepoTags":["a:1"]"""))
    }

    @Test
    fun `a volume needs a name, its driver defaults to local, its use is unknown without the dangling list`() {
        val volumes = parseVolumes("""{"Driver":"local"}""" + "\n" + """{"Name":"v"}""", null)

        assertEquals(listOf("v"), volumes.map { it.name })
        assertEquals("local", volumes.single().driver)
        assertNull(volumes.single().mountpoint)
        assertEquals(emptyMap(), volumes.single().labels)
        assertNull(volumes.single().inUse)
        assertEquals("nfs", parseVolumes("""{"Name":"v","Driver":"nfs"}""", null).single().driver)
        assertEquals(true, parseVolumes("""{"Name":"v"}""", emptySet()).single().inUse)
        assertEquals(false, parseVolumes("""{"Name":"v"}""", setOf("v")).single().inUse)
    }

    @ParameterizedTest
    @CsvSource(
        "'\"Name\":\"web\"', false",
        "'\"Name\":\"web\",\"Labels\":\"com.docker.volume.anonymous=\"', true",
        "'\"Name\":\"web\",\"Labels\":{\"com.docker.volume.anonymous\":\"\"}', true",
        "'\"Name\":\"web\",\"Labels\":\"a=1\"', false",
        "'\"Name\":\"web\",\"Anonymous\":true', true",
        "'\"Name\":\"web\",\"Anonymous\":\"true\"', true",
        "'\"Name\":\"web\",\"Anonymous\":false', false",
        "'\"Name\":\"d2097bae9c8cd083314999fca3448fa0f5537efe2dffb949c07d1241952c3598\"', true",
        "'\"Name\":\"d2097bae9c8cd083314999fca3448fa0f5537efe2dffb949c07d1241952c359\"', false",
        "'\"Name\":\"d2097bae9c8cd083314999fca3448fa0f5537efe2dffb949c07d1241952c35980\"', false",
        "'\"Name\":\"D2097bae9c8cd083314999fca3448fa0f5537efe2dffb949c07d1241952c3598\"', false",
        "'\"Name\":\"x2097bae9c8cd083314999fca3448fa0f5537efe2dffb949c07d1241952c3598\"', false",
    )
    fun `a volume is anonymous by label, by flag or by a 64 digit hex name`(
        fields: String,
        anonymous: Boolean,
    ) {
        assertEquals(anonymous, parseVolumes("{$fields}", null).single().anonymous)
    }

    @Test
    fun `a network needs a name, defaults its id, driver and scope, and reads its labels and time`() {
        val networks =
            parseNetworks(
                """{"Driver":"bridge"}""" + "\n" + """{"Name":"n"}""" + "\n" +
                    """{"ID":"i","Name":"m","Driver":"overlay","Scope":"swarm","Labels":"a=1",""" +
                    """"CreatedAt":"2026-11-01 13:44:03 +0000 UTC"}""" + "\n" +
                    """{"Id":"j","Name":"o","Created":"2026-11-01T13:44:03Z"}""",
            )

        assertEquals(
            listOf(
                Network("n", "n", "", "local"),
                Network("i", "m", "overlay", "swarm", mapOf("a" to "1"), Instant.parse("2026-11-01T13:44:03Z")),
                Network("j", "o", "", "local", emptyMap(), Instant.parse("2026-11-01T13:44:03Z")),
            ),
            networks,
        )
    }
}
