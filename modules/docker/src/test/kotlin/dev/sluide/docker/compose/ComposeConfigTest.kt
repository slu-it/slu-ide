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
        // Jackson's own message follows, so that the user sees where the output broke.
        assertTrue("Unrecognized token" in notJson.message!!, notJson.message)
        val notObject = assertFailsWith<DockerException> { parseComposeConfig(ComposeConfigOutput("[1]", "")) }
        assertTrue("not a project" in notObject.message!!, notObject.message)
        assertFailsWith<DockerException> { parseComposeConfig(ComposeConfigOutput("", "")) }
    }

    private fun parse(json: String) = parseComposeConfig(ComposeConfigOutput(json, ""))

    private fun serviceOf(json: String) = parse("""{"services":{"s":$json}}""").services.single()

    @Test
    fun `a name or an image that is not a string is left out`() {
        val parsed = parse("""{"name":5,"services":{"s":{"image":7}}}""")

        assertEquals("", parsed.name)
        assertNull(parsed.services.single().image)
        assertEquals("", parse("{}").name)
        assertNull(serviceOf("{}").image)
    }

    @Test
    fun `services that are not a map give none`() {
        assertEquals(emptyList(), parse("""{"services":"web"}""").services)
        assertEquals(emptyList(), parse("""{"services":[{"image":"a"}]}""").services)
    }

    @Test
    fun `build counts when it is there and not null`() {
        assertTrue(serviceOf("""{"build":{"context":"."}}""").hasBuild)
        assertTrue(serviceOf("""{"build":"."}""").hasBuild)
        assertFalse(serviceOf("""{"build":null}""").hasBuild)
        assertFalse(serviceOf("{}").hasBuild)
    }

    @Test
    fun `ports, profiles and dependencies that are not lists give none`() {
        val service = serviceOf("""{"ports":"80","profiles":{"a":1},"depends_on":"db"}""")

        assertEquals(emptyList(), service.ports)
        assertEquals(emptySet(), service.profiles)
        assertEquals(emptyList(), service.dependsOn)
        assertEquals(emptyList(), serviceOf("""{"ports":{"target":80}}""").ports)
    }

    @Test
    fun `a port in the short syntax is shown as written`() {
        assertEquals(listOf("8080:80"), serviceOf("""{"ports":["8080:80"]}""").ports)
    }

    @Test
    fun `a port in the long syntax gets its defaults and leaves out empty parts`() {
        val ports =
            serviceOf(
                """{"ports":[
                    {"protocol":"udp"},
                    {"target":80},
                    {"target":81,"published":"","host_ip":"127.0.0.1"},
                    {"target":82,"published":"8082","host_ip":""},
                    {"target":83,"published":8083,"host_ip":"::1","protocol":"udp"},
                    {"target":84,"published":"8084"}
                ]}""",
            ).ports

        assertEquals(listOf("80/tcp", "81/tcp", "8082:82/tcp", "::1:8083:83/udp", "8084:84/tcp"), ports)
    }

    @Test
    fun `dependencies are the names of a map or the entries of a list`() {
        assertEquals(listOf("db", "cache"), serviceOf("""{"depends_on":{"db":{},"cache":{}}}""").dependsOn)
        assertEquals(listOf("db", "cache"), serviceOf("""{"depends_on":["db",{"x":1},"cache"]}""").dependsOn)
        assertEquals(emptyList(), serviceOf("""{"depends_on":null}""").dependsOn)
        assertEquals(emptyList(), serviceOf("""{"depends_on":3}""").dependsOn)
    }

    @Test
    fun `profiles of a service skip entries that are not text`() {
        assertEquals(setOf("a", "b"), serviceOf("""{"profiles":["a",{"x":1},"b","a"]}""").profiles)
    }

    @Test
    fun `a port whose fields are no scalars skips the port or the field`() {
        val ports =
            serviceOf(
                """{"ports":[
                    {"target":[1]},
                    {"target":{"a":1}},
                    {"target":80,"protocol":{"a":1}},
                    {"target":81,"published":["x"],"host_ip":"127.0.0.1"},
                    {"target":82,"published":"8082","host_ip":{"a":1}}
                ]}""",
            ).ports

        assertEquals(listOf("80/tcp", "81/tcp", "8082:82/tcp"), ports)
    }
}
