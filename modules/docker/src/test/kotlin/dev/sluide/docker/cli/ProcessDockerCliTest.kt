package dev.sluide.docker.cli

import dev.sluide.docker.model.ContainerAction
import dev.sluide.docker.model.DockerEvent
import dev.sluide.docker.model.EngineFlavor
import dev.sluide.testsupport.LogCapture
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

    // The stub's answers for the commands that change or list the engine were checked once against the sandbox's
    // Docker 29.10.0: `network prune -f` after creating a network prints "Deleted Networks:\n<name>\n\n", `image prune -f`
    // and `volume prune -f` print "Total reclaimed space: 0B\n", `context show` prints "default\n".
    @BeforeEach
    fun stubCli() {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "no /bin/sh")
        dir.resolve("version.json").writeText(fixture("docker/version.json"))
        dir.resolve("podman-version.json").writeText(fixture("podman/version.json"))
        dir.resolve("ps.jsonl").writeText(fixture("docker/ps.jsonl"))
        dir.resolve("images.jsonl").writeText(fixture("docker/images.jsonl"))
        dir.resolve("dangling.jsonl").writeText(fixture("docker/images-dangling.jsonl"))
        dir.resolve("volumes.jsonl").writeText(fixture("docker/volumes.jsonl"))
        dir.resolve("networks.jsonl").writeText(fixture("docker/networks.jsonl"))
        dir.resolve("inspect.json").writeText(fixture("docker/inspect-container.json"))
        dir.resolve("inspect-volume.json").writeText(fixture("docker/inspect-volume.json"))
        dir.resolve("stats.txt").writeText(fixture("docker/stats-stream.txt"))
        dir.resolve("events.jsonl").writeText(fixture("docker/events.jsonl"))
        dir
            .resolve(
                "version-no-context.json",
            ).writeText("""{"Client":{"Version":"19.03.0"},"Server":{"Version":"19.03.0"}}""")
        dir.resolve("docker").apply {
            writeText(
                """
                #!/bin/sh
                echo "${'$'}*" >> "${'$'}STUB/calls.log"
                case "${'$'}1 ${'$'}2" in
                  "version "*) cat "${'$'}STUB/${'$'}{VERSION:-version.json}" ;;
                  "context show") if [ -n "${'$'}NO_CONTEXT" ]; then echo "context failed" >&2; exit 1; fi; if [ -n "${'$'}CONTEXT_EMPTY" ]; then echo ""; else echo "colima"; fi ;;
                  "network ls") cat "${'$'}STUB/networks.jsonl" ;;
                  "container inspect") cat "${'$'}STUB/inspect.json" ;;
                  "volume inspect") cat "${'$'}STUB/inspect-volume.json" ;;
                  "image prune") echo "Total reclaimed space: 0B" ;;
                  "network prune") printf 'Deleted Networks:\nold-net\n\n' ;;
                  "volume prune") echo "Total reclaimed space: 0B" ;;
                  "stats "*) [ -n "${'$'}JUNK" ] && echo '{"MemUsage":"1B / 2B"}'; cat "${'$'}STUB/stats.txt" ;;
                  "ps "*) cat "${'$'}STUB/ps.jsonl" ;;
                  "images "*) case "${'$'}*" in *dangling*) cat "${'$'}STUB/dangling.jsonl" ;; *) cat "${'$'}STUB/images.jsonl" ;; esac ;;
                  "volume ls") case "${'$'}*" in *dangling*) echo sluidefx-data ;; *) cat "${'$'}STUB/volumes.jsonl" ;; esac ;;
                  "stop "*) echo "Error response from daemon: No such container: ${'$'}2" >&2; exit 1 ;;
                  "pull late"*) echo "Pulling"; exec >&- 2>&-; sleep 1 ;;
                  "pull slow"*) echo ${'$'}${'$'} > "${'$'}STUB/pid"; echo "Pulling"; exec sleep 30 ;;
                  "run "*) case "${'$'}*" in *fail*) echo "boom" >&2; exit 1 ;; *noid*) echo "Unable to find image" >&2 ;; *) echo "Unable to find image" >&2; echo "  id-from-stub  " ;; esac ;;
                  "pull "*) echo "5.2: Pulling from library/bash"; echo "Digest: sha256:abc" >&2; echo "Status: Downloaded newer image" ;;
                  "events "*)
                    if [ -n "${'$'}SPLIT_EVENTS" ]; then
                      printf '{"Type":"container","Action":"st'; sleep 0.3
                      printf 'art","Actor":{"ID":"abc","Attributes":{"name":"web"}}}\n'; exit 0
                    fi
                    if [ -n "${'$'}JUNK" ]; then echo '{"Action":"start"}'; fi
                    if [ -n "${'$'}MANY_EVENTS" ]; then cat "${'$'}STUB/events.jsonl"; exit 0; fi
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
        val lines =
            capturingLog {
                cli().run(listOf("run", "-d", "-e", "TOKEN=s3cret", "bash:5.2"))
                assertFailsWith<DockerException> { cli().run(listOf("run", "-d", "-e", "TOKEN=s3cret", "fail")) }
                cli().run(listOf("run", "-d", "-e", "FOO", "--label", "a=b", "-e", "K=v", "bash:5.2"))
            }

        assertTrue(lines.any { "-e TOKEN=••••" in it }, "the run is logged with a masked value: $lines")
        assertTrue(
            lines.any { "-e FOO --label a=b -e K=•••• bash:5.2" in it },
            "only the value after -e is masked, and only when there is one: $lines",
        )
        assertTrue(lines.none { "s3cret" in it }, lines.toString())
    }

    @Test
    fun `run refuses arguments that do not start with run or are not detached, before starting anything`() {
        assertFailsWith<DockerException> { cli().run(listOf("rm", "-f", "abc")) }
        assertFailsWith<DockerException> { cli().run(emptyList()) }
        val attached = assertFailsWith<IllegalArgumentException> { cli().run(listOf("run", "bash:5.2")) }
        assertEquals("Only detached runs are supported", attached.message)

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

        bounded { cli().pull("bash:5.2") { lines += it } }

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
        capturingLog { logged ->
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
            // The reader logs "closed" when it is done, after the last point at which onEnd could have been called.
            waitForStreamClosed(logged)
        }
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
        capturingLog { logged ->
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
            // The reader logs "closed" when it is done, after the last line it could have delivered.
            waitForStreamClosed(logged)
        }
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

    @Test
    fun `the engine's context comes from context show when the version has none, and stays unknown if that fails`() {
        assertEquals("colima", cli("VERSION" to "version-no-context.json").engine().context)
        assertEquals(listOf("version --format json", "context show"), calls())
        assertNull(cli("VERSION" to "version-no-context.json", "NO_CONTEXT" to "1").engine().context)
        assertEquals("default", cli().engine().context, "a version that names its context needs no second call")
    }

    @Test
    fun `networks, container details and inspects are read and parsed from their commands`() {
        val cli = cli()

        assertEquals(listOf("bridge", "host", "none", "sluidefx-net"), cli.networks().map { it.name })
        assertEquals("sluidefx-web-1", cli.containerDetails("abc").name)
        val pretty = cli.inspect(dev.sluide.docker.model.ResourceKind.VOLUME, "sluidefx-data")
        assertTrue(pretty.startsWith("{\n  \"CreatedAt\": "), pretty)
        assertFailsWith<DockerException> { cli.containerDetails("-x") }
        assertFailsWith<DockerException> { cli.inspect(dev.sluide.docker.model.ResourceKind.VOLUME, " ") }

        assertEquals(
            listOf(
                "network ls --no-trunc --format json",
                "container inspect abc",
                "volume inspect sluidefx-data",
            ),
            calls(),
        )
    }

    @Test
    fun `volumes without the in-use list when its command fails`() {
        // The stub's volume ls fails for the dangling filter when asked to: a CLI that cannot filter.
        dir.resolve("docker").also { script ->
            script.writeText(script.readText().replace("echo sluidefx-data ;;", "echo old >&2; exit 1 ;;"))
        }

        val volumes = cli().volumes()

        assertTrue(volumes.isNotEmpty())
        assertTrue(volumes.all { it.inUse == null })
    }

    @Test
    fun `removals, creations and prunes name their targets and prunes return the CLI's trimmed report`() {
        val cli = cli()

        cli.removeVolume("data")
        cli.removeNetwork("net")
        cli.createVolume("data")
        assertFailsWith<DockerException> { cli.removeNetwork("-f") }
        assertFailsWith<DockerException> { cli.createVolume("") }
        assertFailsWith<DockerException> { cli.createNetwork("net", "-bad") }
        assertFailsWith<DockerException> { cli.removeImage("-f", force = false) }

        assertEquals("Total reclaimed space: 0B", cli.pruneImages())
        assertEquals("Total reclaimed space: 0B", cli.pruneVolumes())
        assertEquals("Deleted Networks:\nold-net", cli.pruneNetworks())
        assertEquals(
            listOf(
                "volume rm data",
                "network rm net",
                "volume create data",
                "image prune -f",
                "volume prune -a -f",
                "network prune -f",
            ),
            calls(),
        )
        assertEquals(
            "Total reclaimed space: 0B",
            cli("VERSION" to "podman-version.json").also { it.engine() }.pruneVolumes(),
        )
    }

    @Test
    fun `a blank driver adds no option to a network`() {
        cli().createNetwork("shop", "  ")

        assertEquals(listOf("network create shop"), calls())
    }

    @Test
    fun `stats samples are delivered, none after the stream was closed inside the callback`() {
        val samples = CopyOnWriteArrayList<Double>()
        val handle = AtomicReference<AutoCloseable>()
        val ready = CountDownLatch(1)
        capturingLog { logged ->
            handle.set(
                cli().stats("web", { sample ->
                    ready.await(5, TimeUnit.SECONDS)
                    samples += sample.cpuPercent
                    handle.get().close()
                }, {}),
            )
            ready.countDown()
            waitForStreamClosed(logged)
        }

        assertEquals(listOf(0.01), samples)
        assertEquals(listOf("stats --format json web"), calls())
    }

    @Test
    fun `all stats samples of a stream arrive and it ends normally`() {
        val samples = CopyOnWriteArrayList<Double>()
        val ended = CountDownLatch(1)

        cli().stats("web", { samples += it.cpuPercent }, { ended.countDown() })

        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertTrue(samples.size >= 3, "samples: $samples")
        assertFailsWith<DockerException> { cli().stats("-x", {}, {}) }
    }

    @Test
    fun `no event is delivered after the stream was closed inside the callback`() {
        val events = CopyOnWriteArrayList<String>()
        val handle = AtomicReference<AutoCloseable>()
        val ready = CountDownLatch(1)
        capturingLog { logged ->
            handle.set(
                cli("MANY_EVENTS" to "1").events({ event ->
                    ready.await(5, TimeUnit.SECONDS)
                    events += event.action
                    handle.get().close()
                }, {}),
            )
            ready.countDown()
            waitForStreamClosed(logged)
        }

        assertEquals(1, events.size)
    }

    @Test
    fun `an object the callback fails on with a JSON error is skipped, the next one still arrives`() {
        val actions = CopyOnWriteArrayList<String>()
        val ended = CountDownLatch(1)

        cli("MANY_EVENTS" to "1").events({ event ->
            actions += event.action
            if (actions.size == 1) dockerJson.readTree("{broken")
        }, { ended.countDown() })

        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertTrue(actions.size > 1, "events: $actions")
    }

    @Test
    fun `logs without a tail ask for all lines, a negative tail for none, and refuse option-like ids`() {
        cli().logs("web", null, {}, {})
        cli().logs("web", -5, {}, {})
        assertFailsWith<DockerException> { cli().logs("-f", null, {}, {}) }

        waitFor { calls().size == 2 }
        assertEquals(
            setOf("logs -f --timestamps web", "logs -f --timestamps --tail 0 web"),
            calls().toSet(),
        )
    }

    @Test
    fun `a context show that prints nothing leaves the context unknown`() {
        assertNull(cli("VERSION" to "version-no-context.json", "CONTEXT_EMPTY" to "1").engine().context)
    }

    @Test
    fun `an image is removed with force, and the executable's name tells Podman before the engine is asked`() {
        cli().removeImage("bash:5.2", force = true)
        Files.copy(dir.resolve("docker"), dir.resolve("podman"))
        val podman =
            ProcessDockerCli(
                ResolvedDocker(dir.resolve("podman"), mapOf("PATH" to "/usr/bin:/bin", "STUB" to dir.toString())),
            )

        podman.pruneVolumes()

        assertEquals(listOf("rmi -f bash:5.2", "volume prune -f"), calls())
    }

    @Test
    fun `a pull that goes on after closing its output still ends normally`() {
        val lines = mutableListOf<String>()

        bounded { cli().pull("late:1") { lines += it } }

        assertEquals(listOf("Pulling"), lines)
    }

    @Test
    fun `run says what it was given when it is no run command`() {
        val rm = assertFailsWith<DockerException> { cli().run(listOf("rm", "-d")) }
        val none = assertFailsWith<DockerException> { cli().run(emptyList()) }

        assertEquals("Not a run command: rm", rm.message)
        assertEquals("Not a run command: ", none.message)
    }

    @Test
    fun `objects that are no event or no sample are skipped, the stream goes on`() {
        val actions = CopyOnWriteArrayList<String>()
        val samples = CopyOnWriteArrayList<Double>()
        val ended = CountDownLatch(2)

        cli("JUNK" to "1", "MANY_EVENTS" to "1").events({ actions += it.action }, { ended.countDown() })
        cli("JUNK" to "1").stats("web", { samples += it.cpuPercent }, { ended.countDown() })

        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertTrue(actions.size > 1, "events: $actions")
        assertTrue(samples.size >= 3, "samples: $samples")
    }

    @Test
    fun `an event cut in two by the pipe is still one event`() {
        val actions = CopyOnWriteArrayList<String>()
        val ended = CountDownLatch(1)

        cli("SPLIT_EVENTS" to "1").events({ actions += it.action }, { ended.countDown() })

        assertTrue(ended.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("start"), actions)
    }

    /** Runs [block] with the processes' logger captured; the messages it logged, with the capture for polling. */
    private fun capturingLog(block: (LogCapture) -> Unit): List<String> =
        LogCapture("dev.sluide.docker.cli.DockerProcesses").use { log ->
            block(log)
            log.messages()
        }

    /** The reader logs "stream closed" once it is done, after the last delivery it could have made. */
    private fun waitForStreamClosed(log: LogCapture) {
        // Only this test's stream started while the log was captured; a reader of an earlier test has another name.
        waitFor { log.messages().any { " stream start " in it } }
        val name = log.messages().first { " stream start " in it }.substringBefore(' ')
        waitFor { log.messages().any { it.startsWith("$name stream closed after") } }
    }

    /** A pull whose progress loop never ends (a mutant that loses the end marker) fails instead of hanging. */
    private fun <T> bounded(block: () -> T): T =
        org.junit.jupiter.api.Assertions
            .assertTimeoutPreemptively(java.time.Duration.ofSeconds(30), block)

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
