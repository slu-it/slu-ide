package dev.sluide.git.cli

import dev.sluide.git.GitCancellation
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration.ofSeconds
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How [GitCli] starts, waits for and stops processes: the environment, the grace times, the output limits and the
 * `core.fsmonitor` guard, with a script that stands in for git (a shell answering `--version`, as GitCliCancelTest).
 */
class GitCliProcessTest {
    @TempDir
    lateinit var folder: Path

    private var scripts = 0

    private val shell =
        "#!/bin/sh\n[ \"\$1\" = --version ] && exit 0\n" +
            "case \" \$* \" in *' --local-env-vars '*) exit 1;; esac\n"

    private fun script(body: String): Path =
        folder.resolve("fake-git-${++scripts}").apply {
            writeText("$shell$body\n")
            toFile().setExecutable(true)
        }

    private fun fakeGit(
        body: String,
        stopGrace: Duration = 2.seconds,
        pipeGrace: Duration = 2.seconds,
        environment: Map<String, String> = System.getenv(),
    ): GitCli =
        GitCli(script(body).toString(), environment, stopGrace, pipeGrace).also {
            assumeTrue(it.available, "no /bin/sh to stand in for git")
        }

    /** Runs [block], failing the test if it takes longer than [seconds]: a regression must not hang the build. */
    private fun <T> within(
        seconds: Long,
        block: () -> T,
    ): T = assertTimeoutPreemptively(ofSeconds(seconds), ThrowingSupplier { block() })

    private fun awaitFile(file: Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!file.exists() || file.readText().isBlank()) {
            if (System.nanoTime() > deadline) fail("$file was not written")
            Thread.sleep(20)
        }
    }

    private fun awaitGone(pid: Long) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) {
            if (System.nanoTime() > deadline) fail("process $pid is still running")
            Thread.sleep(20)
        }
    }

    private fun read(
        git: GitCli,
        vararg arguments: String,
        directory: Path = folder,
        timeout: Duration = 10.seconds,
        cancellation: GitCancellation? = null,
        maxOutput: Long? = null,
    ) = git.execute(
        arguments.toList(),
        directory,
        timeout,
        null,
        write = false,
        cancellation = cancellation,
        maxOutput = maxOutput,
    )

    @Test
    fun `a process gets the given environment only, GIT_EDITOR for a write only, optional locks off for a read only`() {
        assumeTrue(System.getenv("HOME") != null, "no HOME to leave out")
        val shown = "[\$HOME][\$KEEP][\$GIT_EDITOR][\$GIT_OPTIONAL_LOCKS]"
        val probe = "case \" \$* \" in *' probe '*) echo \"$shown\"; exit 0;; esac"
        val git = fakeGit(probe, environment = mapOf("PATH" to System.getenv("PATH"), "KEEP" to "yes"))

        assertEquals("[][yes][][0]\n", read(git, "probe")?.output?.decodeToString())
        assertEquals("[][yes][false][]", git.write(folder, listOf("probe")).message)
        assertEquals(
            "[][yes][:][]",
            git.writeWithExit(folder, listOf("probe"), environment = mapOf("GIT_EDITOR" to ":")).first.message,
        )
    }

    @Test
    fun `the default git is the one on the path with the environment of the process`() {
        val default = GitCli()

        assertEquals(System.getenv("PATH"), default.environment["PATH"])
        assertEquals(GitCli("git", System.getenv()).available, default.available)
    }

    @Test
    fun `the standard error of a write is kept without hints`() {
        val git = fakeGit("echo 'hint: use this' >&2; echo 'fatal: real' >&2; exit 1")

        val output = git.writeWithOutput(folder, listOf("x"))

        assertEquals("fatal: real", output.errors)
        assertEquals(1, output.exitValue)
    }

    @Test
    fun `a git cancelled before it starts is not started and not logged`() {
        val lines = CopyOnWriteArrayList<String>()
        val previous = GitCommandLogSink.sink
        GitCommandLogSink.sink = { _, message, _ -> lines += message }
        try {
            val git = fakeGit("touch '${folder.resolve("started")}'; exit 0")
            lines.clear()

            val execution = read(git, "x", cancellation = GitCancellation().apply { cancel() })

            assertNotNull(execution)
            assertTrue(execution.cancelled)
            assertEquals(emptyList(), lines.toList())
            assertFalse(folder.resolve("started").exists())
        } finally {
            GitCommandLogSink.sink = previous
        }
    }

    @Test
    fun `a git that writes more than allowed is stopped at once, not waited for`() {
        val git = fakeGit("head -c 100000 /dev/zero; exec sleep 30")

        val execution =
            within(15) { read(git, "x", timeout = 60.seconds, maxOutput = 1000) }

        assertNotNull(execution)
        assertTrue(execution.overflowed)
        assertEquals(1000, execution.output.size)
    }

    @Test
    fun `what a process left behind writes within the grace is part of the output, and of the error log`() {
        val git =
            fakeGit(
                "(sleep 0.3; echo late) & ( sleep 0.01; echo late >&2 ) >/dev/null & sleep 0.3; exit 0",
                pipeGrace = 10.seconds,
            )

        val execution = read(git, "x")

        assertNotNull(execution)
        assertTrue(execution.complete)
        assertEquals("late\n", execution.output.decodeToString())
        assertEquals("late\n", execution.errors.decodeToString())
    }

    @Test
    fun `an error output over the limit is read to its end and dropped, so that git is not blocked`() {
        val git = fakeGit("head -c 300000 /dev/zero | tr '\\0' x >&2; exit 0")

        val execution =
            within(30) {
                git.execute(listOf("x"), folder, 20.seconds, null, write = true, maxErrors = 10)
            }

        assertNotNull(execution)
        assertEquals(0, execution.exitValue)
        assertEquals(10, execution.errors.size)
        assertTrue(execution.complete)
    }

    @Test
    fun `a caller interrupted while git runs gets nothing, keeps the interrupt and git is stopped`() {
        for (write in listOf(false, true)) {
            val pid = folder.resolve("pid-$write")
            val git = fakeGit("echo \$\$ > '$pid'; exec sleep 30", stopGrace = 1.seconds)
            val result = CompletableFuture<Pair<GitCli.Execution?, Boolean>>()
            val thread =
                Thread {
                    val execution = git.execute(listOf("x"), folder, 60.seconds, null, write)
                    result.complete(execution to Thread.currentThread().isInterrupted)
                }.apply { start() }
            awaitFile(pid)

            thread.interrupt()

            val (execution, interrupted) = result.get(10, TimeUnit.SECONDS)
            assertNull(execution, "write $write")
            assertTrue(interrupted, "the interrupt is kept, write $write")
            awaitGone(pid.readText().trim().toLong())
        }
    }

    @Test
    fun `a write that ignores SIGTERM is killed when the grace is over`() {
        val pid = folder.resolve("pid")
        val ignoring = "trap '' TERM; echo \$\$ > '$pid'; while true; do sleep 0.1; done"
        val git = fakeGit(ignoring, stopGrace = 300.milliseconds)

        val execution =
            within(15) {
                git.execute(listOf("x"), folder, 500.milliseconds, null, write = true)
            }

        assertNotNull(execution)
        assertNull(execution.exitValue)
        assertFalse(execution.cancelled)
        awaitGone(pid.readText().trim().toLong())
    }

    @Test
    fun `a read that is cancelled is killed at once, without the grace of a write`() {
        val pid = folder.resolve("pid")
        val git = fakeGit("trap '' TERM; echo \$\$ > '$pid'; while true; do sleep 0.1; done", stopGrace = 30.seconds)
        val cancellation = GitCancellation()
        val reading =
            CompletableFuture.supplyAsync { read(git, "x", timeout = 60.seconds, cancellation = cancellation) }
        awaitFile(pid)

        cancellation.cancel()

        val execution = within(10) { reading.get() }
        assertNotNull(execution)
        assertTrue(execution.cancelled)
        awaitGone(pid.readText().trim().toLong())
    }

    @Test
    fun `a write that cleans up on SIGTERM is given two seconds by default`() {
        val ready = folder.resolve("ready")
        val marker = folder.resolve("cleaned")
        val body = "trap 'sleep 0.3; echo done > \"$marker\"; exit 0' TERM; echo ready > '$ready'; sleep 30 & wait"
        val git = GitCli(script(body).toString(), System.getenv())
        assumeTrue(git.available, "no /bin/sh to stand in for git")
        val cancellation = GitCancellation()
        val writing =
            CompletableFuture.supplyAsync {
                git.execute(listOf("x"), folder, 60.seconds, null, write = true, cancellation = cancellation)
            }
        awaitFile(ready)

        cancellation.cancel()

        assertTrue(assertNotNull(writing.get(10, TimeUnit.SECONDS)).cancelled)
        assertEquals("done", marker.readText().trim())
    }

    @Test
    fun `a read keeps the start of what git says on standard error, a write all of it`() {
        val git = fakeGit("head -c 10000 /dev/zero | tr '\\0' x >&2; exit 0")

        val read = read(git, "x")
        val write = git.execute(listOf("x"), folder, 10.seconds, null, write = true)

        assertEquals(STDERR_KEEP, read?.errors?.size)
        assertEquals(10000, write?.errors?.size)
    }

    @Test
    fun `an error output over the limit is drained to its end, git is never blocked or cut off`() {
        val writing = "(head -c 300000 /dev/zero | tr '\\0' x >&2)"
        val body = "trap '' PIPE; if ! $writing; then touch '$folder/cut'; fi; exit 0"
        val git = fakeGit(body)

        val execution = within(30) { git.execute(listOf("x"), folder, 20.seconds, null, write = true, maxErrors = 10) }

        assertEquals(0, execution?.exitValue)
        assertEquals(10, execution?.errors?.size)
        assertFalse(folder.resolve("cut").exists(), "git could not write all it said")
    }

    // The scripts below wait 0.3 s before they end: the JDK drains and closes the output pipe of a process that ends
    // while its reader has not started reading, and the output would then look complete now and then. What a child
    // writes comes after git ended, not before.
    @Test
    fun `output that arrives after git ended is waited for as long as the grace, to the nanosecond`() {
        val late = fakeGit("(sleep 0.7; echo late) & sleep 0.3; exit 0", pipeGrace = 900.milliseconds)
        assertEquals("late\n", read(late, "x")?.output?.decodeToString())
        assertEquals(true, read(late, "x")?.complete)

        val later = fakeGit("(sleep 1.8; echo late) & sleep 0.3; exit 0", pipeGrace = 10.seconds)
        assertEquals("late\n", read(later, "x")?.output?.decodeToString())
        assertEquals(true, read(later, "x")?.complete)
    }

    @Test
    fun `an error line that a left-behind process writes just after git ended is still kept`() {
        // Only standard error is held: the output reader ends with git, the error reader gets its short grace.
        val git = fakeGit("( sleep 0.32; echo late >&2 ) >/dev/null & sleep 0.3; exit 0")

        assertEquals("late\n", read(git, "x")?.errors?.decodeToString())
    }

    @Test
    fun `a caller interrupted while a write is being stopped keeps the interrupt and git ends`() {
        val terming = folder.resolve("terming")
        val pid = folder.resolve("pid")
        val body = "trap 'echo x > \"$terming\"; sleep 3; exit 0' TERM; echo \$\$ > '$pid'; sleep 30 & wait"
        val git = fakeGit(body, stopGrace = 10.seconds)
        val cancellation = GitCancellation()
        val result = CompletableFuture<Pair<GitCli.Execution?, Boolean>>()
        val thread =
            Thread {
                val execution =
                    git.execute(listOf("x"), folder, 60.seconds, null, write = true, cancellation = cancellation)
                result.complete(execution to Thread.currentThread().isInterrupted)
            }.apply { start() }
        awaitFile(pid)
        cancellation.cancel()
        awaitFile(terming)

        thread.interrupt()

        val (execution, interrupted) = result.get(10, TimeUnit.SECONDS)
        assertTrue(assertNotNull(execution).cancelled)
        assertTrue(interrupted, "the interrupt is kept")
        awaitGone(pid.readText().trim().toLong())
    }

    /** What the guard read of `core.fsmonitor` answers, and whether git then runs with the hook turned off, kept. */
    data class Guard(
        val name: String,
        val answer: String,
        val guarded: Boolean,
        val kept: Boolean,
    ) {
        override fun toString() = name
    }

    @ParameterizedTest
    @MethodSource("guards")
    fun `the guard against a core fsmonitor hook is kept for a definite answer only`(case: Guard) {
        val repository = folder.resolve("repo").also { it.resolve(".git").createDirectories() }
        repository.resolve(".git/config").writeText("[core]\n\tbare = false\n")
        val configReads = folder.resolve("config-reads")
        val calls = folder.resolve("calls")
        val body =
            "case \" \$* \" in *' core.fsmonitor '*) echo x >> '$configReads'; ${case.answer};;\n" +
                "*' probe '*) echo \"\$*\" >> '$calls'; exit 0;; esac"
        val git = fakeGit(body, pipeGrace = 100.milliseconds)

        repeat(2) { read(git, "probe", directory = repository) }

        val probes = calls.readText().lines().filter { it.isNotEmpty() }
        assertEquals(case.guarded, probes.all { "-c core.fsmonitor=false" in it }, "${case.name}: $probes")
        assertEquals(case.guarded, probes.any { "-c core.fsmonitor=false" in it }, "${case.name}: $probes")
        assertEquals(if (case.kept) 1 else 2, configReads.readText().lines().count { it.isNotEmpty() }, case.name)
    }

    @Test
    fun `the guard is read again when the configuration changes, and never kept without a git folder`() {
        val repository = folder.resolve("repo").also { it.resolve(".git").createDirectories() }
        val config = repository.resolve(".git/config")
        config.writeText("[core]\n\tbare = false\n")
        val configReads = folder.resolve("config-reads")
        val body =
            "case \" \$* \" in *' core.fsmonitor '*) echo x >> '$configReads'; exit 1;;\n" +
                "*' probe '*) exit 0;; esac"
        val git = fakeGit(body)

        fun reads() = if (configReads.exists()) configReads.readText().lines().count { it.isNotEmpty() } else 0

        read(git, "probe", directory = repository)
        read(git, "probe", directory = repository)
        assertEquals(1, reads(), "kept while the configuration is the same")
        config.writeText("[core]\n\tbare = true\n")
        read(git, "probe", directory = repository)
        assertEquals(2, reads(), "read again for another configuration")

        val plain = folder.resolve("plain").createDirectories()
        read(git, "probe", directory = plain)
        read(git, "probe", directory = plain)
        assertEquals(4, reads(), "never kept without a .git")
    }

    @Test
    fun `a git file or a git folder without a configuration also keeps the guard`() {
        val configReads = folder.resolve("config-reads")
        val body =
            "case \" \$* \" in *' core.fsmonitor '*) echo x >> '$configReads'; exit 1;;\n" +
                "*' probe '*) exit 0;; esac"
        val git = fakeGit(body)
        val linked = folder.resolve("linked").createDirectories()
        linked.resolve(".git").writeText("gitdir: /somewhere/else\n")
        val bare = folder.resolve("bare").also { it.resolve(".git").createDirectories() }

        repeat(2) { read(git, "probe", directory = linked) }
        assertEquals(1, configReads.readText().lines().count { it.isNotEmpty() })
        repeat(2) { read(git, "probe", directory = bare) }
        assertEquals(2, configReads.readText().lines().count { it.isNotEmpty() })
        assertTrue(Files.exists(linked.resolve(".git")))
    }

    companion object {
        @JvmStatic
        fun guards() =
            listOf(
                Guard("a boolean true", "exit 0", guarded = false, kept = true),
                Guard("not set", "exit 1", guarded = false, kept = true),
                Guard("no boolean", "echo 'fatal: bad' >&2; exit 128", guarded = true, kept = true),
                Guard("another failure", "exit 2", guarded = true, kept = false),
                Guard("an answer cut off", "echo true; (sleep 20 &); sleep 0.3; exit 0", guarded = true, kept = false),
            )
    }
}
