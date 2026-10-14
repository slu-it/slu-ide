package dev.sluide.git

import dev.sluide.filewatcher.FileWatcher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/**
 * How [GitStatusService] schedules `git status` and handles its results. A script stands in for git, so the test
 * decides when a run finishes and what it reports: it logs each run, waits while the hold file exists, then prints the
 * status file, or fails if there is none.
 */
class GitStatusServiceRunsTest {
    @TempDir
    lateinit var repo: Path

    @TempDir
    lateinit var scripts: Path

    private val runLog get() = scripts.resolve("runs")
    private val hold get() = scripts.resolve("hold")
    private val status get() = scripts.resolve("status")

    // Everything dispatched runs on this test's thread, like on the JavaFX thread.
    private val dispatched = LinkedBlockingQueue<Runnable>()
    private val watcher = FileWatcher({ dispatched += it }, 50.milliseconds)
    private lateinit var service: GitStatusService

    @BeforeEach
    fun createService() {
        repo.resolve(".git").createDirectories()
        val executable =
            scripts.resolve("git").apply {
                writeText(
                    """
                    #!/bin/sh
                    [ "${'$'}1" = --version ] && exit 0
                    echo run >> '$runLog'
                    while [ -e '$hold' ]; do sleep 0.01; done
                    cat '$status' 2>/dev/null || { echo failed >> '$runLog'; exit 1; }
                    """.trimIndent() + "\n",
                )
                toFile().setExecutable(true)
            }
        service = GitStatusService(GitCli(executable.toString(), System.getenv()), watcher) { dispatched += it }
    }

    @AfterEach
    fun close() {
        service.close()
        watcher.close()
    }

    /** Lets the next `git status` runs report [records]. */
    private fun report(vararg records: String) = status.writeText(records.joinToString("") { "$it\u0000" })

    private fun runLog(): List<String> = if (runLog.exists()) runLog.readLines() else emptyList()

    private fun runs() = runLog().count { it == "run" }

    private fun await(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail(message)
            Thread.sleep(10)
        }
    }

    /** Runs the next dispatched task, e.g. the result of a run. */
    private fun runDispatched() {
        val task = dispatched.poll(10, TimeUnit.SECONDS) ?: fail("Nothing was dispatched")
        task.run()
    }

    private fun awaitSnapshot(condition: (GitStatusSnapshot) -> Boolean): GitStatusSnapshot {
        while (!condition(service.snapshot.get())) runDispatched()
        return service.snapshot.get()
    }

    @Test
    fun `requests during a run are combined into one more run`() {
        report("? a.txt")
        hold.createFile()
        service.open(repo)
        await("git status started") { runs() == 1 }

        repeat(3) { service.refresh() }
        hold.deleteExisting()
        runDispatched()
        runDispatched()
        assertEquals(GitStatus.UNVERSIONED, service.snapshot.get().fileStatus(repo.resolve("a.txt")))

        Thread.sleep(300)
        assertEquals(2, runs())
        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun `results for a folder that is no longer open are dropped`() {
        val other = repo.resolve("other").apply { resolve(".git").createDirectories() }
        report("? a.txt")
        hold.createFile()
        service.open(repo)
        await("git status started") { runs() == 1 }

        service.open(other)
        hold.deleteExisting()
        runDispatched()
        assertSame(GitStatusSnapshot.EMPTY, service.snapshot.get())

        val snapshot = awaitSnapshot { it.root != null }
        assertEquals(other, snapshot.root)
    }

    @Test
    fun `a failing git status keeps the previous snapshot`() {
        report("? a.txt")
        service.open(repo)
        val first = awaitSnapshot { it.root != null }

        status.deleteExisting()
        service.refresh()
        await("git status failed") { "failed" in runLog() }
        report("? b.txt")
        service.refresh()

        // Nothing before the result of the second refresh may replace the snapshot.
        while (service.snapshot.get().fileStatus(repo.resolve("b.txt")) == null) {
            assertSame(first, service.snapshot.get())
            runDispatched()
        }
    }

    @Test
    fun `an unchanged status keeps the same snapshot`() {
        report("? a.txt", "! build/")
        service.open(repo)
        val first = awaitSnapshot { it.root != null }

        service.refresh()
        runDispatched()
        assertSame(first, service.snapshot.get())
    }
}
