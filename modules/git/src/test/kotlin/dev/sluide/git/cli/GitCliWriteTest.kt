package dev.sluide.git.cli

import dev.sluide.git.FAKE_GIT_START
import dev.sluide.git.GitResult
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class GitCliWriteTest {
    @TempDir
    lateinit var folder: Path

    private var fakes = 0

    /** A stand-in for git that answers `--version` and runs [script] for anything else. */
    private fun fakeGit(script: String): GitCli {
        val executable =
            folder.resolve("fake-git-${++fakes}").apply {
                writeText(FAKE_GIT_START + "$script\n")
                toFile().setExecutable(true)
            }
        return GitCli(executable.toString(), System.getenv()).also {
            assumeTrue(it.available, "no /bin/sh to stand in for git")
        }
    }

    @Test
    fun `a write keeps git's standard error, else its output, and has no optional locks variable`() {
        val git = fakeGit("echo out; echo \"  err \$GIT_OPTIONAL_LOCKS|\$GIT_TERMINAL_PROMPT  \" >&2; exit 1")

        assertEquals(GitResult(false, "err |0"), git.write(folder, listOf("x")))

        val quiet = fakeGit("echo '  only output  '; exit 0")
        assertEquals(GitResult(true, "only output"), quiet.write(folder, listOf("x")))

        val silent = fakeGit("exit 3")
        assertEquals(GitResult(false, "git failed with exit code 3."), silent.write(folder, listOf("x")))
    }

    @Test
    fun `a write gets its input and reads keep dropping the standard error`() {
        val withInput = fakeGit("cat >&2; exit 0")
        assertEquals(
            "K\u00f6ln",
            withInput.write(folder, listOf("x"), "K\u00f6ln\n".toByteArray(Charsets.UTF_8)).message,
        )
        val file = folder.resolve("in.txt")
        val reader = fakeGit("echo \"\$GIT_OPTIONAL_LOCKS\" > '$file'; echo err >&2; exit 0")
        assertEquals("", reader.userName(folder).orEmpty())
        assertEquals("0", file.readText().trim())
    }

    @Test
    fun `a write never opens an editor and drops git's hints`() {
        val git = fakeGit("echo \"error: x \$GIT_EDITOR\" >&2; echo 'hint: run git x' >&2; exit 1")
        assertEquals(GitResult(false, "error: x false"), git.write(folder, listOf("x")))

        assertEquals("error: a\nerror: b", withoutHints("error: a\nhint: one\nhint: two\nerror: b"))
        assertEquals("hint: only", withoutHints("hint: only"))
        assertEquals("60 s", shown(60.seconds))
        assertEquals("10 min", shown(600.seconds))
        assertEquals("90 s", shown(90.seconds))
    }

    @Test
    fun `a write that ignores SIGTERM is killed after a grace period`() {
        val pid = folder.resolve("pid")
        val git = fakeGit("echo $$ > '$pid'; trap '' TERM; while true; do sleep 0.1; done")
        val started = System.nanoTime()

        val result = git.write(folder, listOf("x"), timeout = 1.seconds)

        assertEquals(GitResult(false, "git did not finish within 1 s."), result)
        assertTrue(System.nanoTime() - started < 8_000_000_000, "killed after the grace period")
        val process = ProcessHandle.of(pid.readText().trim().toLong())
        // SIGKILL is sent before the write returns, but the process may take a moment to go.
        process.ifPresent { it.onExit().get(5, TimeUnit.SECONDS) }
        assertFalse(process.isPresent && process.get().isAlive, "killed")
    }

    @Test
    fun `a write with large output on both streams does not block`() {
        val git = fakeGit("head -c 1000000 /dev/zero | tr '\\0' a; head -c 1000000 /dev/zero | tr '\\0' b >&2; exit 1")

        val result = git.write(folder, listOf("x"), timeout = 30.seconds)

        assertFalse(result.success)
        assertEquals(1_000_000, result.message.length)
    }

    @Test
    fun `a read whose output is still held open after git ended fails instead of giving what was read`() {
        val git = fakeGit("(sleep 20 &); echo name; exit 0")
        val started = System.nanoTime()

        assertNull(git.userName(folder))
        assertTrue(System.nanoTime() - started < 10_000_000_000)

        val quick = fakeGit("echo name; exit 0")
        assertEquals("name", quick.userName(folder))
    }

    @Test
    fun `a process left behind holding git's output does not keep the write waiting`() {
        val git = fakeGit("(sleep 20 &); echo done >&2; exit 0")
        val started = System.nanoTime()

        val result = git.write(folder, listOf("x"), timeout = 30.seconds)

        assertEquals(GitResult(true, "done"), result)
        assertTrue(System.nanoTime() - started < 10_000_000_000)
    }

    @Test
    fun `a write that takes too long is stopped and says so`() {
        val git = fakeGit("sleep 10")

        val result = git.write(folder, listOf("x"), timeout = 1.seconds)

        assertEquals(GitResult(false, "git did not finish within 1 s."), result)
        // One that changes files may have changed some of them.
        assertEquals(
            GitResult(
                false,
                "git did not finish within 1 s. Files and the index may be partly changed; check git status in a terminal.",
            ),
            git.write(folder, listOf("x"), timeout = 1.seconds, writesFiles = true),
        )
        assertEquals("git did not finish within 10 min.", timedOut(GitCli.CHECKOUT_TIMEOUT, writesFiles = false))
        assertTrue(git.writeWithOutput(folder, listOf("x"), timeout = 1.seconds).timedOut)
        assertFalse(fakeGit("exit 3").writeWithOutput(folder, listOf("x")).timedOut, "a failure is no timeout")
    }
}
