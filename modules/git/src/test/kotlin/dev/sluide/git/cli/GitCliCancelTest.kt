package dev.sluide.git.cli

import dev.sluide.git.FAKE_GIT_START
import dev.sluide.git.GitCancellation
import dev.sluide.git.GitResult
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Cancelling a write through [GitCli] directly, with a stand-in for git. */
class GitCliCancelTest {
    @TempDir
    lateinit var folder: Path

    private fun fakeGit(script: String): GitCli {
        val executable =
            folder.resolve("fake-git").apply {
                writeText(FAKE_GIT_START + "$script\n")
                toFile().setExecutable(true)
            }
        return GitCli(executable.toString(), System.getenv()).also {
            assumeTrue(it.available, "no /bin/sh to stand in for git")
        }
    }

    /** Starts [git] writing with [cancellation] and waits until it wrote its process id to [pid]. */
    private fun startWriting(
        git: GitCli,
        cancellation: GitCancellation?,
        pid: Path,
        timeout: kotlin.time.Duration = GitCli.WRITE_TIMEOUT,
    ): CompletableFuture<Pair<GitResult, Int?>> {
        val writing =
            CompletableFuture.supplyAsync {
                git.writeWithExit(folder, listOf("x"), timeout = timeout, cancellation = cancellation)
            }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!pid.exists() || pid.readText().isBlank()) {
            if (System.nanoTime() > deadline) fail("git did not start")
            Thread.sleep(20)
        }
        return writing
    }

    @Test
    fun `a child that cleans up on SIGTERM gets the grace period after git ended, on a cancel and a timeout`() {
        for (cancel in listOf(true, false)) {
            val pid = folder.resolve("pid-$cancel")
            val marker = folder.resolve("cleaned-$cancel")
            val ready = folder.resolve("ready-$cancel")
            // Git (this script) ends on SIGTERM at once; its child takes a moment to clean up, as a git a hook started
            // removes its lock files, and says so on git's standard error. Git tells it started only once the child set
            // its trap, so that a SIGTERM cannot reach the child first.
            val git =
                fakeGit(
                    "(trap 'sleep 0.3; echo cleaning up >&2; echo done > \"$marker\"; exit 0' TERM; : > '$ready'; " +
                        "while true; do sleep 0.1; done) & " +
                        "until [ -e '$ready' ]; do sleep 0.01; done; echo $$ > '$pid'; while true; do sleep 0.1; done",
                )
            val cancellation = GitCancellation()
            val writing = startWriting(git, cancellation.takeIf { cancel }, pid, if (cancel) 60.seconds else 1.seconds)

            if (cancel) cancellation.cancel()
            val (result, exitValue) = writing.get(10, TimeUnit.SECONDS)

            assertEquals(if (cancel) "Cancelled." else "git did not finish within 1 s.", result.message)
            assertNull(exitValue)
            assertTrue(marker.exists(), "the child cleaned up before it would have been killed (cancel: $cancel)")
        }
    }

    @Test
    fun `a child that ignores SIGTERM and outlives git is killed once the grace period is over`() {
        val pid = folder.resolve("pid")
        val childPid = folder.resolve("child")
        val ready = folder.resolve("ready")
        // Git tells it started only once the child set its trap, so that a SIGTERM cannot reach the child first.
        val git =
            fakeGit(
                "(trap '' TERM; : > '$ready'; while true; do sleep 0.1; done) & echo $! > '$childPid'; " +
                    "until [ -e '$ready' ]; do sleep 0.01; done; echo $$ > '$pid'; while true; do sleep 0.1; done",
            )
        val cancellation = GitCancellation()
        val writing = startWriting(git, cancellation, pid)
        val child = ProcessHandle.of(childPid.readText().trim().toLong())
        assertNotNull(child.orElse(null))
        val started = System.nanoTime()

        cancellation.cancel()
        val (result, _) = writing.get(10, TimeUnit.SECONDS)

        assertEquals(GitResult(false, "Cancelled."), result)
        child.get().onExit().get(5, TimeUnit.SECONDS)
        assertFalse(child.get().isAlive, "killed")
        assertTrue(System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(1500), "only after the grace period")
    }

    @Test
    fun `a process that ended by the time it was cancelled counts as ended`() {
        val process = ProcessBuilder("sh", "-c", "exit 0").start()
        assumeTrue(process.waitFor(10, TimeUnit.SECONDS), "no sh")
        val cancellation = GitCancellation().apply { cancel() }

        assertTrue(awaitEnd(process, 10.seconds, cancellation), "both are done: the process's end wins")

        val running = ProcessBuilder("sh", "-c", "sleep 10").start()
        try {
            assertFalse(awaitEnd(running, 10.seconds, cancellation), "cancelled while it runs")
            assertFalse(awaitEnd(running, 100.milliseconds, null), "still running after the timeout")
        } finally {
            running.destroyForcibly()
        }
    }

    @Test
    fun `the end of a process is seen while the common pool is busy`() {
        // As on a machine with few cores, where the common pool has one or two threads and other work takes them all.
        val pool = ForkJoinPool.commonPool()
        val busy = CountDownLatch(pool.parallelism)
        val release = CountDownLatch(1)
        val tasks =
            List(pool.parallelism) {
                CompletableFuture.runAsync {
                    busy.countDown()
                    while (release.count > 0) Thread.sleep(10)
                }
            }
        try {
            assertTrue(busy.await(10, TimeUnit.SECONDS), "the common pool is busy")
            val process = ProcessBuilder("sh", "-c", "sleep 0.2").start()
            val started = System.nanoTime()

            assertTrue(awaitEnd(process, 10.seconds, GitCancellation()))
            assertTrue(
                System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5),
                "seen when it ended, not at the timeout",
            )
        } finally {
            release.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        }
    }
}
