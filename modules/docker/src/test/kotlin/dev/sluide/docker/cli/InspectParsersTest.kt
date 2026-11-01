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
}
