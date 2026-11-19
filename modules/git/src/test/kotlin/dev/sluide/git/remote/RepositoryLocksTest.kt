package dev.sluide.git.remote

import dev.sluide.git.GitCancellation
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.function.ThrowingSupplier
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration.ofSeconds
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [RepositoryLocks]: one holder per repository folder within the JVM, and the file lock beside it for other apps. */
class RepositoryLocksTest {
    @TempDir
    lateinit var folder: Path

    private val never = GitCancellation()

    private class Waiter(
        val thread: Thread,
        val result: CompletableFuture<String?>,
        val interruptedAfter: AtomicBoolean,
    )

    /** Starts a thread that takes the lock of [directory] and runs [action]; its result is that of `withLock`. */
    private fun waiter(
        directory: Path,
        cancellation: GitCancellation = never,
        action: () -> String = { "ran" },
    ): Waiter {
        val result = CompletableFuture<String?>()
        val interrupted = AtomicBoolean()
        val thread =
            Thread.ofPlatform().daemon().start {
                try {
                    result.complete(RepositoryLocks.withLock(directory, cancellation, action))
                } catch (e: Throwable) {
                    result.completeExceptionally(e)
                }
                interrupted.set(Thread.currentThread().isInterrupted)
            }
        return Waiter(thread, result, interrupted)
    }

    /** Waits until [thread] sleeps or waits with a timeout, as a caller does that finds the lock taken. */
    private fun awaitWaiting(thread: Thread) {
        assertTimeoutPreemptively(
            ofSeconds(30),
            ThrowingSupplier {
                while (thread.state != Thread.State.TIMED_WAITING) {
                    check(thread.isAlive) { "the caller ended without waiting" }
                    Thread.onSpinWait()
                }
            },
        )
    }

    /** A thread that holds the lock of [directory] in its action until [release]. */
    private fun holder(
        directory: Path,
        release: CountDownLatch,
    ): CompletableFuture<String?> {
        val holding = CountDownLatch(1)
        val result =
            waiter(directory) {
                holding.countDown()
                release.await(60, TimeUnit.SECONDS)
                "held"
            }.result
        assertTrue(holding.await(30, TimeUnit.SECONDS), "the holder has the lock")
        return result
    }

    @Test
    fun `the action runs and its result is given, with the folder and the lock file made`() {
        val directory = folder.resolve("a/b/repo")

        val result = RepositoryLocks.withLock(directory, never) { 42 }

        assertEquals(42, result)
        assertTrue(folder.resolve("a/b/repo.lock").exists())
        assertFalse(directory.exists(), "only the lock file is made, not the repository")
        assertEquals("again", RepositoryLocks.withLock(directory, never) { "again" })
    }

    @Test
    fun `a second caller for the folder, spelled in other case, waits until the first has finished`() {
        val release = CountDownLatch(1)
        val first = holder(folder.resolve("Repo"), release)
        val ran = AtomicBoolean()
        val second = waiter(folder.resolve("REPO")) { ran.set(true).let { "second" } }

        awaitWaiting(second.thread)

        assertFalse(ran.get(), "the second action waits")
        release.countDown()
        assertEquals("second", second.result.get(30, TimeUnit.SECONDS))
        assertEquals("held", first.get(30, TimeUnit.SECONDS))
    }

    @Test
    fun `callers for other folders do not wait for each other`() {
        val release = CountDownLatch(1)
        val first = holder(folder.resolve("one"), release)
        try {
            val second = RepositoryLocks.withLock(folder.resolve("two"), never) { "second" }

            assertEquals("second", second)
        } finally {
            release.countDown()
            first.get(30, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `a caller cancelled while waiting for the folder gets null and its action does not run`() {
        val release = CountDownLatch(1)
        val first = holder(folder.resolve("repo"), release)
        val cancellation = GitCancellation()
        val ran = AtomicBoolean()
        val second = waiter(folder.resolve("repo"), cancellation) { ran.set(true).let { "second" } }
        try {
            awaitWaiting(second.thread)

            cancellation.cancel()

            assertNull(second.result.get(30, TimeUnit.SECONDS))
            assertFalse(ran.get())
        } finally {
            release.countDown()
            first.get(30, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `a caller that is interrupted while waiting gets null, and keeps the interrupt`() {
        val release = CountDownLatch(1)
        val first = holder(folder.resolve("repo"), release)
        val second = waiter(folder.resolve("repo"))
        try {
            awaitWaiting(second.thread)

            second.thread.interrupt()

            assertNull(second.result.get(30, TimeUnit.SECONDS))
            second.thread.join(30_000)
            assertTrue(second.interruptedAfter.get(), "the interrupt is kept for the caller")
        } finally {
            release.countDown()
            first.get(30, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `the lock is free again after an action that threw, and the exception is the caller's`() {
        val directory = folder.resolve("repo")

        assertFailsWith<IllegalStateException> { RepositoryLocks.withLock(directory, never) { error("boom") } }

        val count = AtomicInteger()
        val after =
            assertTimeoutPreemptively(
                ofSeconds(30),
                ThrowingSupplier { RepositoryLocks.withLock(directory, never) { count.incrementAndGet() } },
            )
        assertEquals(1, after)
    }

    /** The lock file of another running app: a process that holds the file lock until its input is closed. */
    private fun otherApp(lockFile: Path): Process {
        val source =
            folder.resolve("Hold.java").apply {
                writeText(
                    """
                    import java.nio.channels.FileChannel;
                    import java.nio.file.Path;
                    import java.nio.file.StandardOpenOption;
                    public class Hold {
                        public static void main(String[] args) throws Exception {
                            FileChannel channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                            System.out.println(channel.tryLock() != null ? "locked" : "failed");
                            System.out.flush();
                            System.in.read();
                        }
                    }
                    """.trimIndent(),
                )
            }
        val java = Path.of(System.getProperty("java.home"), "bin", "java")
        assumeTrue(java.exists(), "no java launcher")
        val process = ProcessBuilder(java.toString(), source.toString(), lockFile.toString()).start()
        val line =
            assertTimeoutPreemptively(
                ofSeconds(60),
                ThrowingSupplier { process.inputStream.bufferedReader().readLine() },
            )
        assumeTrue(line == "locked", "the other app did not get the file lock: $line")
        return process
    }

    @Test
    fun `a caller waits for the file lock of another app and runs when it lets go`() {
        val directory = folder.resolve("repo")
        val app = otherApp(folder.resolve("repo.lock"))
        try {
            val waiting = waiter(directory)
            awaitWaiting(waiting.thread)
            assertFalse(waiting.result.isDone, "the file lock is taken")

            app.outputStream.close()

            assertEquals("ran", waiting.result.get(60, TimeUnit.SECONDS))
        } finally {
            app.destroyForcibly()
        }
    }

    @Test
    fun `a caller cancelled while waiting for the file lock gets null and frees the folder`() {
        val directory = folder.resolve("repo")
        val app = otherApp(folder.resolve("repo.lock"))
        try {
            val cancellation = GitCancellation()
            val ran = AtomicBoolean()
            val waiting = waiter(directory, cancellation) { ran.set(true).let { "ran" } }
            awaitWaiting(waiting.thread)

            cancellation.cancel()

            assertNull(waiting.result.get(60, TimeUnit.SECONDS))
            assertFalse(ran.get())
            app.outputStream.close()
            app.waitFor(60, TimeUnit.SECONDS)
            assertEquals("ran", RepositoryLocks.withLock(directory, never) { "ran" }, "the folder's lock was released")
        } finally {
            app.destroyForcibly()
        }
    }

    @Test
    fun `a caller interrupted while waiting for the file lock gets null and keeps the interrupt`() {
        val directory = folder.resolve("repo")
        val app = otherApp(folder.resolve("repo.lock"))
        try {
            val waiting = waiter(directory)
            awaitWaiting(waiting.thread)

            waiting.thread.interrupt()

            assertNull(waiting.result.get(60, TimeUnit.SECONDS))
            waiting.thread.join(30_000)
            assertTrue(waiting.interruptedAfter.get())
        } finally {
            app.destroyForcibly()
        }
    }
}
