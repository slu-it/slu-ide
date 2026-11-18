package dev.sluide.docker.cli

import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.EngineFlavor
import dev.sluide.docker.model.EnvVariable
import dev.sluide.docker.model.Mount
import dev.sluide.docker.model.NetworkAttachment
import dev.sluide.docker.model.PortMapping
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InspectParsersTest {
    @Test
    fun `Docker's version names the engine and the context`() {
        val engine = parseEngine(fixture("docker/version.json"))

        assertEquals(EngineFlavor.DOCKER, engine.flavor)
        assertEquals("29.8.1", engine.serverVersion)
        assertEquals("29.8.1", engine.clientVersion)
        assertEquals("Docker Engine - Community", engine.platform)
        assertEquals("default", engine.context)
    }

    @Test
    fun `Podman's version is recognised by its client part, with or without a server part`() {
        val remote = parseEngine(fixture("podman/version.json"))
        assertEquals(EngineFlavor.PODMAN, remote.flavor)
        assertEquals("5.2.2", remote.serverVersion)
        assertEquals("5.2.3", remote.clientVersion)
        assertNull(remote.context)

        val local = parseEngine("""{"Client":{"APIVersion":"4.9.3","Version":"4.9.3","OsArch":"linux/amd64"}}""")
        assertEquals(EngineFlavor.PODMAN, local.flavor)
        assertEquals("4.9.3", local.serverVersion)
    }

    @Test
    fun `a version without any version fails`() {
        assertFailsWith<DockerException> { parseEngine("") }
        assertFailsWith<DockerException> { parseEngine("""{"Client":{}}""") }
        assertFailsWith<DockerException> { parseEngine("{}") }
        assertFailsWith<DockerException> { parseEngine("""{"Server":{}}""") }
    }

    @Test
    fun `Docker's container details`() {
        val details = parseContainerDetails(fixture("docker/inspect-container.json"))

        assertEquals("e0d50c8aebff1cec6bd7e7d294d46111fccc94bba16bde24da0de25b4a8c3181", details.id)
        assertEquals("sluidefx-web-1", details.name)
        assertEquals("bash:5.2", details.image)
        assertTrue(details.imageId!!.startsWith("sha256:"))
        assertEquals(ContainerState.RUNNING, details.state)
        assertEquals(Instant.parse("2026-11-01T13:44:03.42134665Z"), details.startedAt)
        assertNull(details.finishedAt, "Go's zero time means never")
        assertNull(details.exitCode, "it runs")
        assertEquals(0, details.restartCount)
        assertEquals("healthy", details.health)
        assertEquals(listOf("docker-entrypoint.sh"), details.entrypoint)
        assertEquals("sh", details.command.first())
        assertEquals(
            listOf(EnvVariable("GREETING", "hello"), EnvVariable("SECRET_TOKEN", "abc123"), EnvVariable("EMPTY", "")),
            details.env.take(3),
        )
        assertEquals("PATH", details.env.last { it.name == "PATH" }.name)
        assertEquals("sluidefx", details.labels["com.docker.compose.project"])
        assertEquals(
            listOf("sluidefx-data" to "/data", null to "/data2"),
            details.mounts
                .map {
                    it.name.takeIf { name ->
                        name == "sluidefx-data"
                    } to it.destination
                }.sortedBy { it.second },
        )
        assertTrue(details.mounts.all { it.type == "volume" && it.readWrite })
        assertEquals(listOf("sluidefx-net"), details.networks.map { it.name })
        assertTrue(
            details.networks
                .single()
                .ipAddress!!
                .isNotEmpty(),
        )
        assertEquals(
            setOf(
                PortMapping("127.0.0.1", 18080, 80, "tcp"),
                PortMapping("0.0.0.0", 18443, 443, "tcp"),
                PortMapping("::", 18443, 443, "tcp"),
            ),
            details.ports.toSet(),
        )
    }

    @Test
    fun `Podman's container details, ended and unhealthy`() {
        val details = parseContainerDetails(fixture("podman/inspect-container.json"))

        assertEquals("shop_web_1", details.name)
        assertEquals("docker.io/library/nginx:1.27", details.image)
        assertEquals(ContainerState.EXITED, details.state)
        assertEquals(137, details.exitCode)
        assertEquals(2, details.restartCount)
        assertEquals("unhealthy", details.health)
        assertEquals(Instant.parse("2023-11-14T22:00:00Z"), details.finishedAt)
        assertEquals(listOf("/docker-entrypoint.sh"), details.entrypoint, "a single text")
        assertEquals(listOf("nginx", "-g", "daemon off;"), details.command)
        assertEquals(EnvVariable("container", "podman"), details.env.last())
        assertEquals(
            listOf(
                Mount(
                    "volume",
                    "shop_data",
                    "/home/me/.local/share/containers/storage/volumes/shop_data/_data",
                    "/usr/share/nginx/html",
                    readWrite = false,
                ),
            ),
            details.mounts,
        )
        assertEquals(
            listOf(NetworkAttachment("shop_default", "10.89.0.2", listOf("web", "8f2e6c1d4b3a"))),
            details.networks,
        )
        assertEquals(listOf(PortMapping(null, 8080, 80, "tcp")), details.ports)
    }

    @Test
    fun `exposed ports without a binding are listed once, unpublished`() {
        val details =
            parseContainerDetails(
                """[{"Id":"x","State":{"Status":"created"},"NetworkSettings":{"Ports":{"9000/udp":null}}}]""",
            )

        assertEquals(listOf(PortMapping(null, null, 9000, "udp")), details.ports)
    }

    @Test
    fun `inspect output is pretty-printed with two spaces and a single object unwrapped`() {
        val pretty = prettyInspect(fixture("docker/inspect-volume.json"))

        assertTrue(pretty.startsWith("{\n  \"CreatedAt\": \"2026-11-01T13:44:03Z\",\n"), pretty)
        assertEquals("[\n  1,\n  2\n]", prettyInspect("[1,2]"))
        assertEquals("not json", prettyInspect("not json\n"))
    }

    @Test
    fun `the engine is Podman by OsArch or APIVersion in the client part, Docker's ApiVersion does not count`() {
        assertEquals(EngineFlavor.PODMAN, parseEngine("""{"Client":{"APIVersion":"4.9","Version":"4.9"}}""").flavor)
        assertEquals(EngineFlavor.PODMAN, parseEngine("""{"Client":{"OsArch":"linux/arm64","Version":"4.9"}}""").flavor)
        val docker = parseEngine("""{"Client":{"ApiVersion":"1.50","Version":"29.1","Context":"colima"}}""")
        assertEquals(EngineFlavor.DOCKER, docker.flavor)
        assertEquals("colima", docker.context)
        assertNull(parseEngine("""{"Client":{"OsArch":"linux/arm64","Version":"4","Context":"x"}}""").context)
        assertEquals(EngineFlavor.DOCKER, parseEngine("""{"Server":{"Version":"28.0"}}""").flavor)
    }

    @Test
    fun `the engine's version is the server's, else the client's, and the client's alone is optional`() {
        val serverOnly = parseEngine("""{"Server":{"Version":"28.0","Platform":{"Name":"Docker Engine"}}}""")
        assertEquals("28.0", serverOnly.serverVersion)
        assertNull(serverOnly.clientVersion)
        assertEquals("Docker Engine", serverOnly.platform)
        val clientOnly = parseEngine("""{"Client":{"Version":"27.1"},"Server":{"Platform":{"Name":"x"}}}""")
        assertEquals("27.1", clientOnly.serverVersion)
        assertEquals("27.1", clientOnly.clientVersion)
        assertNull(parseEngine("""{"Client":{"Version":"1"}}""").platform)
    }

    @Test
    fun `output without a container fails`() {
        assertFailsWith<DockerException> { parseContainerDetails("") }
        assertFailsWith<DockerException> { parseContainerDetails("[]") }
    }

    @Test
    fun `a container with nothing but an id has empty details`() {
        val details = parseContainerDetails("""[{"Id":"x"}]""")

        assertEquals("x", details.id)
        assertEquals("", details.name)
        assertEquals("", details.image)
        assertNull(details.imageId)
        assertEquals(ContainerState.UNKNOWN, details.state)
        assertNull(details.createdAt)
        assertNull(details.startedAt)
        assertNull(details.finishedAt)
        assertNull(details.exitCode)
        assertEquals(0, details.restartCount)
        assertNull(details.health)
        assertEquals(emptyList(), details.command)
        assertEquals(emptyList(), details.entrypoint)
        assertEquals(emptyList(), details.env)
        assertEquals(emptyMap(), details.labels)
        assertEquals(emptyList(), details.mounts)
        assertEquals(emptyList(), details.networks)
        assertEquals(emptyList(), details.ports)
        assertEquals("", parseContainerDetails("""[{"Name":"/n"}]""").id)
    }

    @Test
    fun `the image is the config's, else Podman's ImageName, else the image id`() {
        val config = parseContainerDetails("""[{"Image":"sha256:1","ImageName":"b","Config":{"Image":"a"}}]""")
        assertEquals("a" to "sha256:1", config.image to config.imageId)
        val podman = parseContainerDetails("""[{"Image":"sha256:1","ImageName":"b"}]""")
        assertEquals("b" to "sha256:1", podman.image to podman.imageId)
        assertEquals("sha256:1", parseContainerDetails("""[{"Image":"sha256:1"}]""").image)
    }

    @Test
    fun `the exit code is kept for exited and dead containers only, also when it is 0`() {
        fun exit(
            status: String,
            code: Int,
        ) = parseContainerDetails("""[{"State":{"Status":"$status","ExitCode":$code}}]""").exitCode

        assertEquals(0, exit("exited", 0))
        assertEquals(5, exit("dead", 5))
        assertNull(exit("running", 3))
        assertNull(exit("created", 3))
        assertNull(exit("paused", 3))
    }

    @Test
    fun `times, the created one included, are read from the container and its state`() {
        val details =
            parseContainerDetails(
                """[{"Created":"2026-11-01T13:44:00Z","State":{"Status":"exited",""" +
                    """"StartedAt":"2026-11-01T13:44:03Z","FinishedAt":"2026-11-01T13:45:00Z"}}]""",
            )

        assertEquals(Instant.parse("2026-11-01T13:44:00Z"), details.createdAt)
        assertEquals(Instant.parse("2026-11-01T13:44:03Z"), details.startedAt)
        assertEquals(Instant.parse("2026-11-01T13:45:00Z"), details.finishedAt)
    }

    @Test
    fun `environment entries split at the first equals sign, one without it has an empty value`() {
        val details = parseContainerDetails("""[{"Config":{"Env":["A=b=c","FLAG","=x","E="]}}]""")

        assertEquals(
            listOf(EnvVariable("A", "b=c"), EnvVariable("FLAG", ""), EnvVariable("", "x"), EnvVariable("E", "")),
            details.env,
        )
    }

    @Test
    fun `a mount needs a destination, and is read-write unless it says RW false`() {
        val details =
            parseContainerDetails(
                """[{"Mounts":[{"Type":"bind","Source":"/x"},{"Destination":"/a"},""" +
                    """{"Type":"bind","Source":"/s","Destination":"/b","RW":false},{"Destination":"/c","RW":"false"}]}]""",
            )

        assertEquals(
            listOf(
                Mount("", null, null, "/a", readWrite = true),
                Mount("bind", null, "/s", "/b", readWrite = false),
                Mount("", null, null, "/c", readWrite = false),
            ),
            details.mounts,
        )
    }

    @Test
    fun `ports are read from their keys, a key that is no port is skipped`() {
        val details =
            parseContainerDetails(
                """[{"NetworkSettings":{"Ports":{"abc/tcp":[{"HostPort":"1"}],"/tcp":null,"53":null,""" +
                    """"80/tcp":[{"HostIp":"","HostPort":"8080"},{"HostIp":"::"}],"81/tcp":[],"82/tcp":{"a":1},""" +
                    """"83/tcp":"x"}}}]""",
            )

        assertEquals(
            listOf(
                PortMapping(null, null, 53, "tcp"),
                PortMapping(null, 8080, 80, "tcp"),
                PortMapping("::", null, 80, "tcp"),
                PortMapping(null, null, 81, "tcp"),
                PortMapping(null, null, 82, "tcp"),
                PortMapping(null, null, 83, "tcp"),
            ),
            details.ports,
        )
    }

    @Test
    fun `present but empty parts give the same empty details as missing ones`() {
        val empty =
            parseContainerDetails(
                """[{"Id":"x","Config":{},"State":{},"Mounts":null,"NetworkSettings":{"Networks":null,"Ports":null}}]""",
            )

        assertEquals(parseContainerDetails("""[{"Id":"x"}]"""), empty)
    }

    @Test
    fun `a port binding that is an object, not an array, leaves the port unpublished`() {
        val details =
            parseContainerDetails(
                """[{"NetworkSettings":{"Ports":{"82/tcp":{"x":{"HostPort":"9"}}}}}]""",
            )

        assertEquals(listOf(PortMapping(null, null, 82, "tcp")), details.ports)
    }

    @Test
    fun `a network without addresses and aliases is still listed`() {
        val details = parseContainerDetails("""[{"NetworkSettings":{"Networks":{"bridge":{}}}}]""")

        assertEquals(listOf(NetworkAttachment("bridge", null, emptyList())), details.networks)
    }

    @Test
    fun `pretty inspect keeps an object, an array of several and an empty array as they are`() {
        assertEquals("{\n  \"a\": 1\n}", prettyInspect("""{"a":1}"""))
        val two = prettyInspect("""[{"a":1},{"b":2}]""")
        assertEquals("[\n  {\n    \"a\": 1\n  },\n  {\n    \"b\": 2\n  }\n]", two)
        assertEquals("[ ]", prettyInspect("[]"))
        assertEquals("{\n  \"a\": 1\n}", prettyInspect("""[{"a":1}]"""))
    }
}
