package dev.sluide.docker.cli

import dev.sluide.docker.model.ContainerAction
import dev.sluide.docker.model.DockerEvent
import dev.sluide.docker.model.EngineFlavor
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** [ProcessDockerCli] running a stub `docker` script that answers from the fixtures and logs its arguments. */
class ProcessDockerCliTest {
    @TempDir
    lateinit var dir: Path

    private val log by lazy { dir.resolve("calls.log") }
    private val pidFile by lazy { dir.resolve("pid") }

    @BeforeEach
    fun stubCli() {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "no /bin/sh")
        dir.resolve("version.json").writeText(fixture("docker/version.json"))
        dir.resolve("podman-version.json").writeText(fixture("podman/version.json"))
        dir.resolve("ps.jsonl").writeText(fixture("docker/ps.jsonl"))
        dir.resolve("images.jsonl").writeText(fixture("docker/images.jsonl"))
        dir.resolve("dangling.jsonl").writeText(fixture("docker/images-dangling.jsonl"))
        dir.resolve("volumes.jsonl").writeText(fixture("docker/volumes.jsonl"))
        dir.resolve("docker").apply {
            writeText(
                """
                #!/bin/sh
                echo "${'$'}*" >> "${'$'}STUB/calls.log"
                case "${'$'}1 ${'$'}2" in
                  "version "*) cat "${'$'}STUB/${'$'}{VERSION:-version.json}" ;;
                  "ps "*) cat "${'$'}STUB/ps.jsonl" ;;
                  "images "*) case "${'$'}*" in *dangling*) cat "${'$'}STUB/dangling.jsonl" ;; *) cat "${'$'}STUB/images.jsonl" ;; esac ;;
                  "volume ls") case "${'$'}*" in *dangling*) echo sluidefx-data ;; *) cat "${'$'}STUB/volumes.jsonl" ;; esac ;;
                  "stop "*) echo "Error response from daemon: No such container: ${'$'}2" >&2; exit 1 ;;
                  "pull slow"*) echo ${'$'}${'$'} > "${'$'}STUB/pid"; echo "Pulling"; exec sleep 30 ;;
                  "run "*) case "${'$'}*" in *fail*) echo "boom" >&2; exit 1 ;; *noid*) echo "Unable to find image" >&2 ;; *) echo "Unable to find image" >&2; echo "  id-from-stub  " ;; esac ;;
                  "pull "*) echo "5.2: Pulling from library/bash"; echo "Digest: sha256:abc" >&2; echo "Status: Downloaded newer image" ;;
                  "events "*)
                    echo '{"Type":"container","Action":"start","Actor":{"ID":"abc","Attributes":{"name":"web"}}}'
                    if [ -n "${'$'}END_EVENTS" ]; then echo "error during connect: engine gone" >&2; exit 2; fi
                    echo ${'$'}${'$'} > "${'$'}STUB/pid"; exec sleep 30 ;;
                  "logs "*)
                    case "${'$'}*" in
                      *many*) echo ${'$'}${'$'} > "${'$'}STUB/pid"; seq 1 5000; exec sleep 30 ;;
                      *) echo "2026-11-01T13:44:26Z out"; echo "2026-11-01T13:44:27Z err" >&2 ;;
                    esac ;;
                  "compose "*)
                    case "${'$'}*" in
                      *manywarn*) echo "{}"; cat "${'$'}STUB/warnings.txt" >&2 ;;
                      *broken*) echo "service \"web\" depends on undefined service \"nope\"" >&2; exit 1 ;;
                      *) echo "{\"cwd\":\"${'$'}(pwd)\",\"var\":\"${'$'}SLUIDE_VAR\",\"keep\":\"${'$'}STUB\"}"
                         echo "time=\"t\" level=warning msg=\"careful\"" >&2 ;;
                    esac ;;
                  "network inspect") echo "slow" ; echo ${'$'}${'$'} > "${'$'}STUB/pid"; exec sleep 30 ;;
                  *) ;;
                esac
                """.trimIndent() + "\n",
            )
            setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    private fun cli(vararg extra: Pair<String, String>) =
        ProcessDockerCli(
            ResolvedDocker(dir.resolve("docker"), mapOf("PATH" to "/usr/bin:/bin", "STUB" to dir.toString()) + extra),
            readTimeout = 1000.milliseconds,
        )

    private fun calls() = if (log.exists()) log.readLines() else emptyList()

    @Test
    fun `listings parse what the CLI prints, run with the resolved environment`() {
        val cli = cli()

        assertEquals("default", cli.engine().context, "the context comes with the version")
        assertEquals(4, cli.containers().size)
        assertTrue(cli.images().any { it.dangling }, "dangling images come from their own listing")
        assertEquals(
            mapOf("sluidefx-data" to false),
            cli.volumes().filter { it.name == "sluidefx-data" }.associate {
                it.name to
                    it.inUse
            },
        )
        assertEquals(
            listOf(
                "version --format json",
                "ps -a --no-trunc --format json",
                "images --no-trunc --digests --format json",
                "images --no-trunc --digests --filter dangling=true --format json",
                "volume ls --format json",
                "volume ls -q --filter dangling=true",
            ),
            calls(),
        )
    }

    @Test
    fun `compose config runs in the folder, adds the variables to the environment and returns both outputs`() {
        val folder = dir.resolve("project").also { Files.createDirectories(it) }

        val output =
            cli().composeConfig(
                folder,
                listOf("compose", "-f", "/x/-weird.yaml", "--profile", "*"),
                mapOf("SLUIDE_VAR" to "a b"),
            )

        assertEquals(
            """{"cwd":"${folder.toRealPath()}","var":"a b","keep":"$dir"}""",
            output.json.trim(),
            "the resolved environment stays, the variable is added",
        )
        assertEquals("""time="t" level=warning msg="careful"""", output.warnings.trim())
        assertEquals(listOf("compose -f /x/-weird.yaml --profile * config --format json"), calls())
    }

    private fun warningLines(count: Int) =
        (1..count).joinToString("") { """time="t" level=warning msg="The \"V$it\" variable is not set."""" + "\n" }

    @Test
    fun `compose config keeps long warning output whole, up to a larger cap cut at a line end`() {
        dir.resolve("warnings.txt").writeText(warningLines(1000))
        val all = cli().composeConfig(dir, listOf("compose", "-f", "/x/manywarn.yaml"), emptyMap()).warnings
        assertEquals(1000, all.lines().count { it.isNotEmpty() }, "far more than the 4 KB of other commands")

        dir.resolve("warnings.txt").writeText(warningLines(5000))
        val capped = cli().composeConfig(dir, listOf("compose", "-f", "/x/manywarn.yaml"), emptyMap()).warnings

        assertTrue(capped.length in 32_000..CAPTURED_ERROR_BYTES, "capped at ${capped.length}")
        assertTrue(capped.endsWith("\n"), "the cut line is dropped")
        assertTrue(capped.lines().filter { it.isNotEmpty() }.all { it.endsWith("variable is not set.\"") })
    }

    @Test
    fun `compose config with an environment a process cannot get fails as a DockerException`() {
        assertFailsWith<DockerException> { cli().composeConfig(dir, listOf("compose"), mapOf("A=B" to "x")) }
        assertFailsWith<DockerException> { cli().composeConfig(dir, listOf("compose"), mapOf("A" to "x\u0000y")) }
    }

    @Test
    fun `compose config failing throws the CLI's error`() {
        val failure =
            assertFailsWith<DockerException> {
                cli().composeConfig(dir, listOf("compose", "-f", "/x/broken.yaml"), emptyMap())
            }

        assertEquals("service \"web\" depends on undefined service \"nope\"", failure.message)
        assertEquals(1, failure.exitCode)
    }

    @Test
    fun `run starts a container and returns its trimmed id, with values inline`() {
        val id = cli().run(listOf("run", "-d", "-e", "A=b c", "bash:5.2"))

        assertEquals("id-from-stub", id)
        assertEquals(listOf("run -d -e A=b c bash:5.2"), calls())
    }

    @Test
    fun `run keeps the variable values out of the IDE log, also when it fails`() {
        val lines = CopyOnWriteArrayList<String>()
        val logger =
            java.util.logging.Logger
                .getLogger("dev.sluide.docker.cli.DockerProcesses")
        val handler =
            object : java.util.logging.Handler() {
                override fun publish(record: java.util.logging.LogRecord) {
                    lines += record.message
                }

                override fun flush() {}

                override fun close() {}
            }
        val level = logger.level
        logger.level = java.util.logging.Level.ALL
        logger.addHandler(handler)
        try {
            cli().run(listOf("run", "-d", "-e", "TOKEN=s3cret", "bash:5.2"))
            assertFailsWith<DockerException> { cli().run(listOf("run", "-d", "-e", "TOKEN=s3cret", "fail")) }
        } finally {
            logger.removeHandler(handler)
            logger.level = level
        }

        assertTrue(lines.any { "-e TOKEN=••••" in it }, "the run is logged with a masked value: $lines")
        assertTrue(lines.none { "s3cret" in it }, lines.toString())
    }

    @Test
    fun `run refuses arguments that do not start with run or are not detached, before starting anything`() {
        assertFailsWith<DockerException> { cli().run(listOf("rm", "-f", "abc")) }
        assertFailsWith<DockerException> { cli().run(emptyList()) }
        assertFailsWith<IllegalArgumentException> { cli().run(listOf("run", "bash:5.2")) }

        assertEquals(emptyList(), calls(), "nothing was started")
    }

    @Test
    fun `run that prints no container id fails`() {
        val failure = assertFailsWith<DockerException> { cli().run(listOf("run", "-d", "noid")) }

        assertEquals("The engine printed no container id", failure.message)
    }

    @Test
    fun `a failing command throws the CLI's error with its exit code`() {
        val failure = assertFailsWith<DockerException> { cli().act(ContainerAction.STOP, "nope") }

        assertEquals("Error response from daemon: No such container: nope", failure.message)
        assertEquals(1, failure.exitCode)
    }

    @Test
    fun `a command that takes too long is killed`() {
        val failure =
            assertFailsWith<DockerException> { cli().inspect(dev.sluide.docker.model.ResourceKind.NETWORK, "x") }

        assertTrue("did not finish" in failure.message!!, failure.message)
        assertNull(failure.exitCode)
        val pid = pidFile.readText().trim().toLong()
        ProcessHandle.of(pid).ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "the CLI is gone")
    }

    @Test
    fun `actions, removals and prunes pass their options, and names that look like options are refused`() {
        val cli = cli()

        cli.removeContainer("abc", volumes = true, force = true)
        cli.removeContainer("abc", volumes = false, force = false)
        cli.removeImage("bash:5.2", force = false)
        cli.createNetwork("shop", "bridge")
        cli.createNetwork("plain", null)
        cli.pruneVolumes()
        assertFailsWith<DockerException> { cli.removeVolume("-rf") }

        assertEquals(
            listOf(
                "rm -v -f abc",
                "rm abc",
                "rmi bash:5.2",
                "network create --driver bridge shop",
                "network create plain",
                "volume prune -a -f",
            ),
            calls(),
        )
    }

    @Test
    fun `Podman prunes every unused volume without --all`() {
        val cli = cli("VERSION" to "podman-version.json")

        assertEquals(EngineFlavor.PODMAN, cli.engine().flavor)
        cli.pruneVolumes()

        assertEquals(listOf("version --format json", "volume prune -f"), calls())
    }

    @Test
    fun `a pull reports each progress line, standard error too`() {
        val lines = mutableListOf<String>()

        cli().pull("bash:5.2") { lines += it }

        assertEquals(
            listOf("5.2: Pulling from library/bash", "Digest: sha256:abc", "Status: Downloaded newer image"),
            lines,
        )
    }

    @Test
    fun `the events stream delivers events, and closing it ends the CLI without calling onEnd`() {
        val events = CopyOnWriteArrayList<DockerEvent>()
        val ends = CopyOnWriteArrayList<Throwable?>()
        val first = CountDownLatch(1)

        val stream =
            cli().events({
                events += it
                first.countDown()
            }, { ends += it })
        assertTrue(first.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("container start web"), events.map { "${it.type} ${it.action} ${it.name}" })
        waitFor { pidFile.exists() && pidFile.readText().isNotBlank() }
        val pid = pidFile.readText().trim().toLong()

        stream.close()

        ProcessHandle.of(pid).ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "the CLI is gone")
        // No signal tells that onEnd will not come; the reader ends right after the CLI.
        Thread.sleep(200)
        assertEquals(emptyList(), ends)
    }

    @Test
    fun `a stream that fails ends with the CLI's error`() {
        val ended = CountDownLatch(1)
        var failure: Throwable? = null

        cli("END_EVENTS" to "1").events({}, {
            failure = it
            ended.countDown()
        })

        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals("error during connect: engine gone", failure?.message)
        assertEquals(2, (failure as DockerException).exitCode)
    }

    @Test
    fun `logs mix standard error in and end normally when the CLI exits 0`() {
        val lines = CopyOnWriteArrayList<String>()
        val ended = CountDownLatch(1)
        var failure: Throwable? = IllegalStateException("not ended")

        cli().logs("web", 100, { lines += it }, {
            failure = it
            ended.countDown()
        })

        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertNull(failure)
        assertEquals(setOf("2026-11-01T13:44:26Z out", "2026-11-01T13:44:27Z err"), lines.toSet())
        assertEquals(listOf("logs -f --timestamps --tail 100 web"), calls())
    }

    @Test
    fun `no line arrives after the handle is closed, also of what was still buffered`() {
        val lines = CopyOnWriteArrayList<String>()
        val ends = CopyOnWriteArrayList<Throwable?>()
        val handle = AtomicReference<AutoCloseable>()
        val opened = CountDownLatch(1)

        // The CLI prints 5000 lines at once; the first one closes the stream, the rest sits in the pipe and buffer.
        handle.set(
            cli().logs("many", null, { line ->
                opened.await(5, TimeUnit.SECONDS)
                lines += line
                if (lines.size == 1) handle.get().close()
            }, { ends += it }),
        )
        opened.countDown()

        waitFor { lines.isNotEmpty() && pidFile.exists() && pidFile.readText().isNotBlank() }
        val pid = pidFile.readText().trim().toLong()
        ProcessHandle.of(pid).ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
        // No signal tells that no more lines will come; the reader ends right after the CLI.
        Thread.sleep(200)
        assertEquals(listOf("1"), lines)
        assertEquals(emptyList(), ends)
    }

    @Test
    fun `a pull whose progress callback throws, or whose thread is interrupted, kills the CLI`() {
        val failure = assertFailsWith<IllegalStateException> { cli().pull("slow:1") { error("stop") } }
        assertEquals("stop", failure.message)
        assertGone(pidFile.readText().trim().toLong())
        pidFile.toFile().delete()

        val pulled = CompletableFuture<Throwable?>()
        val thread =
            Thread.ofVirtual().start {
                pulled.complete(runCatching { cli().pull("slow:2") {} }.exceptionOrNull())
            }
        waitFor { pidFile.exists() && pidFile.readText().isNotBlank() }
        thread.interrupt()

        assertTrue("interrupted" in (pulled.get(5, TimeUnit.SECONDS) as DockerException).message!!)
        assertGone(pidFile.readText().trim().toLong())
    }

    private fun assertGone(pid: Long) {
        ProcessHandle.of(pid).ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "the CLI is gone")
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition not met in time")
    }
}
