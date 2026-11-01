package dev.sluide.docker.compose

import dev.sluide.docker.cli.ComposeConfigOutput
import dev.sluide.docker.cli.DockerException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fixtures: the output of `docker compose --profile '*' config --format json` of Compose v5.5.1, paths shortened. */
internal fun composeFixture(name: String): String =
    requireNotNull(
        object {}.javaClass.getResource("/dev/sluide/docker/compose/$name"),
    ) { "no fixture $name" }.readText()

class ComposeConfigTest {
    private val config =
        parseComposeConfig(
            ComposeConfigOutput(composeFixture("config-all-profiles.json"), composeFixture("config-warnings.txt")),
        )

    private fun service(name: String) = config.services.single { it.name == name }

    @Test
    fun `the project name and all services are read, those of every profile included`() {
        assertEquals("demo", config.name)
        assertEquals(listOf("app", "db", "debug", "web", "worker"), config.services.map { it.name })
    }

    @Test
    fun `profiles are collected from the services`() {
        assertEquals(setOf("debug", "jobs", "tools"), config.profiles)
        assertEquals(setOf("debug", "tools"), service("debug").profiles)
        assertEquals(emptySet(), service("web").profiles)
    }

    @Test
    fun `ports are shown as published colon target and protocol`() {
        assertEquals(listOf("8080:80/tcp", "127.0.0.1:8443:443/tcp", "5353:53/udp"), service("web").ports)
        assertEquals(listOf("5432/tcp"), service("db").ports, "a port that is not published")
        assertEquals(emptyList(), service("app").ports)
    }

    @Test
    fun `image, build and dependencies`() {
        assertEquals("nginx:1.27", service("web").image)
        assertFalse(service("web").hasBuild)
        assertTrue(service("app").hasBuild)
        assertNull(service("app").image)
        assertEquals(listOf("db"), service("web").dependsOn)
        assertEquals(listOf("db"), service("app").dependsOn)
        assertEquals(emptyList(), service("db").dependsOn)
    }

    @Test
    fun `stderr gives unresolved variables apart from the other warnings`() {
        assertEquals(setOf("GREETING"), config.unresolvedVariables)
        assertEquals(1, config.warnings.size)
        assertTrue("attribute `version` is obsolete" in config.warnings.single(), config.warnings.single())
    }

    @Test
    fun `plain stderr lines are kept as they are and blank ones dropped`() {
        val (unresolved, others) =
            parseComposeWarnings("\n WARN[0000] something odd \ntime=\"t\" level=warning msg=\"say \\\"hi\\\"\"\n")

        assertEquals(emptySet(), unresolved)
        assertEquals(listOf("WARN[0000] something odd", "say \"hi\""), others)
    }

    @Test
    fun `a project without services parses`() {
        val parsed = parseComposeConfig(ComposeConfigOutput("""{"name":"empty"}""", ""))

        assertEquals("empty", parsed.name)
        assertEquals(emptyList(), parsed.services)
    }

    @Test
    fun `output that is not a project is a clear error`() {
        val notJson = assertFailsWith<DockerException> { parseComposeConfig(ComposeConfigOutput("services:", "")) }
        assertTrue("not JSON" in notJson.message!!, notJson.message)
        val notObject = assertFailsWith<DockerException> { parseComposeConfig(ComposeConfigOutput("[1]", "")) }
        assertTrue("not a project" in notObject.message!!, notObject.message)
        assertFailsWith<DockerException> { parseComposeConfig(ComposeConfigOutput("", "")) }
    }
}
