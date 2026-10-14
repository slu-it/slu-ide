package dev.sluide.git

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class GitCliTest {
    @TempDir
    lateinit var folder: Path

    @Test
    fun `a missing executable is not available and gives no status`() {
        val git = GitCli(folder.resolve("no-such-git").toString(), System.getenv())
        assertFalse(git.available)
        assertNull(git.status(folder))
    }

    @Test
    fun `status fails outside a repository`() {
        // Git must not find a repository above the temporary folder.
        val git = GitCli("git", testGitEnvironment() + ("GIT_CEILING_DIRECTORIES" to folder.parent.toString()))
        assumeTrue(git.available, "git is not installed")
        assertNull(git.status(folder))
    }

    @Test
    fun `an interrupted run stops git, what it started, and keeps the interrupt`() {
        val pidFile = folder.resolve("pid")
        val childFile = folder.resolve("child")
        // Answers --version; for anything else it starts a sleeping child, writes both process ids and waits.
        val executable =
            folder.resolve("slow-git").apply {
                writeText(
                    "#!/bin/sh\n[ \"$1\" = --version ] && exit 0\n" +
                        "sleep 10 &\necho $! > '$childFile'\necho $$ > '$pidFile'\nwait\n",
                )
                toFile().setExecutable(true)
            }
        val git = GitCli(executable.toString(), System.getenv())
        assumeTrue(git.available, "no /bin/sh to stand in for git")

        var result: String? = "not finished"
        var interrupted = false
        val caller =
            Thread.ofPlatform().start {
                result = git.status(folder)
                interrupted = Thread.currentThread().isInterrupted
            }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (pidFile.readTextOrNull().isNullOrBlank()) {
            if (System.nanoTime() > deadline) fail("git did not start")
            Thread.sleep(20)
        }
        val process = ProcessHandle.of(pidFile.readText().trim().toLong())
        val child = ProcessHandle.of(childFile.readText().trim().toLong())
        assertTrue(process.isPresent, "git runs")
        assertTrue(child.isPresent, "the child of git runs")

        caller.interrupt()
        caller.join(TimeUnit.SECONDS.toMillis(10))

        assertFalse(caller.isAlive)
        assertNull(result)
        assertTrue(interrupted, "the interrupt is kept")
        process.get().onExit().get(10, TimeUnit.SECONDS)
        assertFalse(process.get().isAlive, "git was stopped")
        // Well before the child would end by itself.
        child.get().onExit().get(5, TimeUnit.SECONDS)
        assertFalse(child.get().isAlive, "the child of git was stopped")
    }
}

private fun Path.readTextOrNull(): String? = if (exists()) readText() else null

/**
 * The environment for git in tests: no global or system configuration, so the user's settings do not interfere, and
 * none of the inherited `GIT_*` variables. A build run from a git hook or `git rebase -x` inherits `GIT_DIR`, which
 * would point the tests' git commands at the SluIde repository itself.
 */
internal fun testGitEnvironment(): Map<String, String> =
    System.getenv().filterKeys { !it.startsWith("GIT_") } +
        mapOf("GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_CONFIG_NOSYSTEM" to "1")
