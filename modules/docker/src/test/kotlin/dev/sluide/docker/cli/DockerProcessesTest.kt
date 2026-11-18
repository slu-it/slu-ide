package dev.sluide.docker.cli

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** [DockerProcesses] running small `/bin/sh` scripts in place of the CLI. */
class DockerProcessesTest {
    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun needsSh() {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "no /bin/sh")
    }

    private val baseEnvironment = mapOf("PATH" to "/usr/bin:/bin")

    /** A CLI named `stub` that runs [body] with the arguments as `$@`. */
    private fun processes(
        body: String,
        environment: Map<String, String> = baseEnvironment,
    ): DockerProcesses {
        val script = dir.resolve("stub")
        script.writeText("#!/bin/sh\n$body\n")
        script.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
        return DockerProcesses(script, environment)
    }

    private fun DockerProcesses.read(
        vararg arguments: String,
        timeout: Duration = 5.seconds,
        onLine: ((String) -> Unit)? = null,
        onErrors: ((String) -> Unit)? = null,
    ) = run(arguments.toList(), CommandKind.READ, timeout, onLine = onLine, onErrors = onErrors)

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(condition(), "condition not met in time")
    }

    private fun assertGone(pid: Long) {
        ProcessHandle.of(pid).ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "process $pid is gone")
    }

    private fun pidIn(name: String): Long {
        val file = dir.resolve(name)
        waitFor { Files.exists(file) && file.readText().isNotBlank() }
        return file.readText().trim().toLong()
    }

    @Test
    fun `a run that cannot start fails with where and why`() {
        val missing = DockerProcesses(Path.of("/nonexistent/docker"), baseEnvironment)

        val failure = assertFailsWith<DockerException> { missing.read("ps") }

        assertEquals("Cannot run /nonexistent/docker: ${failure.cause!!.message}", failure.message)
        assertTrue(failure.cause!!.message!!.isNotBlank())
        assertIs<IOException>(failure.cause)
    }

    @Test
    fun `an environment a process cannot get fails with the reason`() {
        val failure =
            assertFailsWith<DockerException> {
                processes("true", mapOf("A=B" to "x")).read("ps")
            }

        assertTrue(failure.message!!.startsWith("Invalid environment for ${dir.resolve("stub")}: "), failure.message)
        assertEquals(failure.cause!!.message, failure.message!!.substringAfter(": "))
    }

    @Test
    fun `the CLI gets the given environment and nothing of the IDE's`() {
        assumeTrue(System.getenv("HOME") != null, "no HOME to leave out")
        val cli =
            processes(
                """echo "home=${'$'}{HOME:-unset} var=${'$'}{VAR:-unset}"""",
                baseEnvironment + ("VAR" to "1"),
            )

        assertEquals("home=unset var=1\n", cli.read("x"))
    }

    @Test
    fun `a timeout names the program and its first argument, an interrupt too, and the thread stays interrupted`() {
        val cli = processes("""echo ${'$'}${'$'} > "${dir.resolve("pid")}"; exec sleep 30""")

        val timedOut = assertFailsWith<DockerException> { cli.read("slow", "more", timeout = 300.milliseconds) }
        assertEquals("stub slow did not finish within 300ms", timedOut.message)
        assertNull(timedOut.exitCode)
        assertGone(pidIn("pid"))
        Files.delete(dir.resolve("pid"))
        val noArguments = assertFailsWith<DockerException> { cli.read(timeout = 300.milliseconds) }
        assertEquals("stub  did not finish within 300ms", noArguments.message)
        assertGone(pidIn("pid"))
        Files.delete(dir.resolve("pid"))

        val outcome = CompletableFuture<Pair<Throwable?, Boolean>>()
        val thread =
            Thread.ofVirtual().start {
                val failure = runCatching { cli.read("slow", onLine = {}, timeout = Duration.INFINITE) }
                outcome.complete(failure.exceptionOrNull() to Thread.currentThread().isInterrupted)
            }
        val pid = pidIn("pid")
        thread.interrupt()

        val (failure, interrupted) = outcome.get(5, TimeUnit.SECONDS)
        assertEquals("stub slow was interrupted", failure?.message)
        assertTrue(interrupted, "the interrupt is not swallowed")
        assertGone(pid)
        Files.delete(dir.resolve("pid"))

        val withoutArguments = CompletableFuture<Throwable?>()
        val second =
            Thread.ofVirtual().start {
                withoutArguments.complete(
                    runCatching { cli.read(onLine = {}, timeout = Duration.INFINITE) }.exceptionOrNull(),
                )
            }
        pidIn("pid")
        second.interrupt()

        assertEquals("stub  was interrupted", withoutArguments.get(5, TimeUnit.SECONDS)?.message)
    }

    @Test
    fun `killing a timed out CLI kills what it started`() {
        val cli = processes("""sleep 30 & echo ${'$'}! > "${dir.resolve("child")}"; wait""")

        assertFailsWith<DockerException> { cli.read("x", timeout = 500.milliseconds) }

        assertGone(pidIn("child"))
    }

    @Test
    fun `a failing command's message is its standard error, cut to 4 KB, or says the exit code`() {
        val cli =
            processes(
                """case "${'$'}1" in big) head -c 10000 /dev/zero | tr '\0' x >&2 ;; blank) echo "  " >&2 ;; esac; exit 3""",
            )

        val big = assertFailsWith<DockerException> { cli.read("big") }
        assertEquals(4096, big.message!!.length)
        assertEquals(3, big.exitCode)
        assertEquals("stub blank exited with code 3", assertFailsWith<DockerException> { cli.read("blank") }.message)
        assertEquals("stub  exited with code 3", assertFailsWith<DockerException> { cli.read() }.message)
    }

    @Test
    fun `with a line callback a failing command's message is the last five lines, standard error included`() {
        val cli = processes("""seq 1 8; echo "oops" >&2; exit 4""")
        val lines = mutableListOf<String>()

        val failure = assertFailsWith<DockerException> { cli.read("pull", onLine = { lines += it }) }

        assertEquals("5\n6\n7\n8\noops".lines().size, failure.message!!.lines().size)
        assertTrue(failure.message!!.lines().containsAll(listOf("oops", "8", "5")), failure.message)
        assertFalse("4" in failure.message!!.lines())
        assertEquals(4, failure.exitCode)
        assertEquals(9, lines.size)
        val silent = processes("exit 5")
        assertEquals(
            "stub pull exited with code 5",
            assertFailsWith<DockerException> {
                silent.read("pull", onLine = {})
            }.message,
        )
    }

    @Test
    fun `the whole output comes back, also a large one, and with a line callback every line once`() {
        val cli = processes("seq 1 200000")
        val lines = mutableListOf<String>() // onLine runs on the calling thread

        val text = cli.read("x")
        val withLines = cli.read("x", onLine = { lines += it })

        assertEquals(200000, text.lines().count { it.isNotEmpty() })
        assertTrue(text.endsWith("200000\n"))
        assertEquals(text, withLines)
        assertEquals(200000, lines.size)
    }

    @Test
    fun `standard error of a successful run goes to onErrors whole, unless a line callback is given`() {
        val cli = processes("""printf 'first\nlast without newline' >&2; echo out""")
        val errors = mutableListOf<String>()

        assertEquals("out\n", cli.read("x", onErrors = { errors += it }))
        cli.read("x", onLine = {}, onErrors = { errors += it })

        assertEquals(listOf("first\nlast without newline"), errors)
    }

    @Test
    fun `a stream ends with null after the CLI exits 0, with its message after a failure, with its exit code else`() {
        val cli =
            processes(
                """case "${'$'}1" in fail) echo "bad thing" >&2; exit 2 ;; silent) exit 3 ;; *) echo hi ;; esac""",
            )

        fun end(vararg arguments: String): Throwable? {
            val ended = CompletableFuture<Throwable?>()
            val lines = CopyOnWriteArrayList<String>()
            cli.stream(
                arguments.toList(),
                mergeErrors = false,
                consume = { reader, _ -> reader.forEachLine { lines += it } },
                onEnd = { ended.complete(it) },
            )
            return ended.get(5, TimeUnit.SECONDS)
        }

        assertNull(end("ok"))
        val failure = assertIs<DockerException>(end("fail"))
        assertEquals("bad thing", failure.message)
        assertEquals(2, failure.exitCode)
        assertEquals("stub silent exited with code 3", end("silent")?.message)
    }

    @Test
    fun `a stream whose reader fails ends with that failure, and the CLI is killed`() {
        val cli = processes("""echo ${'$'}${'$'} > "${dir.resolve("pid")}"; exec sleep 30""")
        val ended = CompletableFuture<Throwable?>()

        cli.stream(
            listOf("x"),
            mergeErrors = false,
            consume = { _, _ -> throw IllegalStateException("reader broke") },
            onEnd = { ended.complete(it) },
        )

        assertEquals("reader broke", ended.get(5, TimeUnit.SECONDS)?.message)
        assertGone(pidIn("pid"))
    }

    @Test
    fun `a stream whose reader throws an IOException ends with it once the CLI is done`() {
        val cli = processes("true")
        val ended = CompletableFuture<Throwable?>()

        cli.stream(
            listOf("x"),
            mergeErrors = false,
            consume = { _, _ -> throw IOException("pipe broke") },
            onEnd = { ended.complete(it) },
        )

        assertEquals("pipe broke", assertIs<IOException>(ended.get(5, TimeUnit.SECONDS)).message)
    }

    @Test
    fun `a stream thread that is interrupted while the CLI runs kills it and ends with exit code -1`() {
        val cli = processes("""echo ${'$'}${'$'} > "${dir.resolve("pid")}"; exec sleep 30""")
        val ended = CompletableFuture<Throwable?>()

        cli.stream(listOf("x"), mergeErrors = false, consume = { _, _ -> }, onEnd = { ended.complete(it) })
        val pid = pidIn("pid")
        var thread: Thread? = null
        waitFor {
            thread =
                Thread.getAllStackTraces().keys.firstOrNull {
                    it.name.startsWith(
                        "stub#",
                    ) && it.name.endsWith("-stream")
                }
            thread != null
        }
        thread?.interrupt()

        assertEquals(-1, assertIs<DockerException>(ended.get(5, TimeUnit.SECONDS)).exitCode)
        assertGone(pid)
        assertNotNull(thread)
    }

    @Test
    fun `closing a stream before the CLI ended ends no callback and kills the CLI`() {
        val cli = processes("""echo ${'$'}${'$'} > "${dir.resolve("pid")}"; exec sleep 30""")
        val ends = CopyOnWriteArrayList<Throwable?>()

        val handle =
            cli.stream(listOf("x"), mergeErrors = true, consume = { reader, _ -> reader.read() }, onEnd = {
                ends +=
                    it
            })
        val pid = pidIn("pid")
        handle.close()

        assertGone(pid)
        assertEquals(emptyList(), ends)
    }
}
