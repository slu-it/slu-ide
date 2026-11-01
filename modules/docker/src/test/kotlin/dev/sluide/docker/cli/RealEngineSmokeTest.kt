package dev.sluide.docker.cli

import dev.sluide.docker.compose.ComposeCommands
import dev.sluide.docker.compose.ComposeOptions
import dev.sluide.docker.compose.parseComposeConfig
import dev.sluide.docker.model.ContainerAction
import dev.sluide.docker.model.DockerEvent
import dev.sluide.docker.model.ResourceKind
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ProcessDockerCli] against the real engine, opt-in: skipped unless `SLUIDE_DOCKER_SMOKE` is set (docs/testing.md),
 * `docker` is on the `PATH`, the engine answers and a small image is there already (nothing is pulled). Creates one
 * container of its own, named `sluide-smoke-…`, and removes it again; touches nothing else.
 */
class RealEngineSmokeTest {
    private lateinit var cli: ProcessDockerCli
    private lateinit var image: String

    @BeforeEach
    fun realEngine() {
        assumeTrue(System.getenv("SLUIDE_DOCKER_SMOKE") != null, "opt-in: set SLUIDE_DOCKER_SMOKE=1")
        val executable =
            System
                .getenv("PATH")
                ?.split(':')
                ?.map { Path.of(it, "docker") }
                ?.firstOrNull { Files.isExecutable(it) }
        assumeTrue(executable != null, "no docker on the PATH")
        cli = ProcessDockerCli(ResolvedDocker(executable!!, System.getenv()))
        assumeTrue(runCatching { cli.engine() }.isSuccess, "the Docker engine does not answer")
        val references = cli.images().map { it.reference }.toSet()
        image =
            listOf("bash:5.2", "bash:3.2", "debian:stable", "alpine:latest", "busybox:latest").firstOrNull {
                it in
                    references
            }
                ?: return assumeTrue(false, "no small image present")
    }

    @Test
    fun `the listings of the real engine parse`() {
        assertTrue(cli.engine().serverVersion.isNotBlank())
        cli.containers()
        assertTrue(cli.images().isNotEmpty())
        cli.volumes()
        assertTrue(cli.networks().any { it.builtin })
    }

    @Test
    fun `a container is created, started, stopped and removed, and the events stream sees it start`() {
        val name = "sluide-smoke-${System.nanoTime()}"
        val events = CopyOnWriteArrayList<DockerEvent>()
        val started = CountDownLatch(1)
        val ends = CopyOnWriteArrayList<Throwable?>()
        val stream =
            cli.events({ event ->
                events += event
                if (event.type == "container" && event.action == "start" && event.name == name) started.countDown()
            }, { ends += it })
        val id = createContainer(name)
        try {
            cli.act(ContainerAction.START, id)
            // The CLI connects its stream asynchronously; nothing tells when. A restart starts the container again for
            // a stream that connected only after the first start.
            var restarts = 0
            while (!started.await(3, TimeUnit.SECONDS) && restarts++ < 3) cli.act(ContainerAction.RESTART, id)
            assertEquals(0, started.count, "start event seen; events: ${events.map { it.action }}")
            val listed = cli.containers().single { it.name == name }
            assertEquals(id, listed.id)
            assertEquals(name, cli.containerDetails(id).name)
            assertTrue(cli.inspect(ResourceKind.CONTAINER, id).startsWith("{"))
            cli.act(ContainerAction.STOP, id)
        } finally {
            cli.removeContainer(id, volumes = true, force = true)
            stream.close()
        }
        assertTrue(cli.containers().none { it.name == name })
        assertEquals(emptyList(), ends, "a closed stream reports no end")
    }

    @Test
    fun `compose config of the real CLI lists the services of every profile`(
        @org.junit.jupiter.api.io.TempDir folder: Path,
    ) {
        val file = folder.resolve("compose.yaml")
        Files.writeString(
            file,
            "name: sluide-smoke\nservices:\n  web:\n    image: nginx\n    environment:\n      - A=\${A}\n" +
                "  tool:\n    image: busybox\n    profiles: [tools]\n",
        )
        val args = ComposeCommands.baseArgs(file, null, ComposeOptions(), allProfiles = true)

        val config = parseComposeConfig(cli.composeConfig(folder, args, emptyMap()))

        assertEquals("sluide-smoke", config.name)
        assertEquals(listOf("tool", "web"), config.services.map { it.name })
        assertEquals(setOf("A"), config.unresolvedVariables)
    }

    /**
     * `docker create` is not part of [DockerCli]; the smoke test runs it itself, like a user would. The container's
     * shell ends at once on the stop signal, which `sleep` as the container's first process would ignore for 10 s.
     */
    private fun createContainer(name: String): String {
        val command = "trap 'exit 0' TERM; while :; do sleep 0.1; done"
        val docker =
            ProcessBuilder(
                "docker",
                "create",
                "--name",
                name,
                image,
                "sh",
                "-c",
                command,
            ).redirectErrorStream(true).start()
        val output =
            docker.inputStream
                .bufferedReader()
                .readText()
                .trim()
        assertTrue(docker.waitFor(30, TimeUnit.SECONDS) && docker.exitValue() == 0, output)
        return output.lines().last()
    }
}
