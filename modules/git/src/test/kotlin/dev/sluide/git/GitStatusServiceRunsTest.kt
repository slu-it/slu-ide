package dev.sluide.git

import dev.sluide.filewatcher.FileWatcher
import dev.sluide.git.cli.GitCli
import dev.sluide.projecttrust.ProjectTrust
import dev.sluide.projecttrust.ProjectTrustCheck
import dev.sluide.projecttrust.ProjectTrustState
import dev.sluide.projecttrust.TrustFinding
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
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
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

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
                    FAKE_GIT_START +
                        """
                        echo run >> '$runLog'
                        while [ -e '$hold' ]; do sleep 0.01; done
                        cat '$status' 2>/dev/null || { echo failed >> '$runLog'; exit 1; }
                        """.trimIndent() + "\n",
                )
                toFile().setExecutable(true)
            }
        val git = GitCli(executable.toString(), System.getenv())
        service = GitStatusService(git, watcher, testTrust()) { dispatched += it }
        assumeTrue(git.available, "no /bin/sh to stand in for git")
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

    @Test
    fun `status cycles and batches of changes in git are logged`() {
        val lines = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val previous = GitStatusLog.sink
        GitStatusLog.sink = { lines += Thread.currentThread().name to it }
        try {
            report("? a.txt")
            service.open(repo)
            awaitSnapshot { it.root != null }
            service.refresh()
            runDispatched()
            repo.resolve(".git/HEAD").writeText("ref: refs/heads/x\n")
            await("the batch was logged") {
                if (dispatched.isNotEmpty()) runDispatched()
                lines.any { "Changes in .git" in it.second }
            }
        } finally {
            GitStatusLog.sink = previous
        }

        val cycles = lines.map { it.second }.filter { it.startsWith("Git status cycle for $repo") }
        assertTrue(cycles.any { it.endsWith("started") })
        assertTrue(
            cycles.any { Regex(": trust awaited \\d+ ms, status \\d+ ms, changed").containsMatchIn(it) },
            "$cycles",
        )
        assertTrue(cycles.any { it.endsWith(", unchanged") }, "$cycles")
        val batch = lines.first { "Changes in .git" in it.second }
        assertTrue("Changes in .git of $repo: " in batch.second && "HEAD" in batch.second, batch.second)
        assertEquals("sluide-git", batch.first, "the batch is logged off the JavaFX thread")
    }

    /** A check standing in for git's: it finds [findings], which the test may change. */
    private class FakeGitCheck(
        @Volatile var findings: List<TrustFinding> = emptyList(),
    ) : ProjectTrustCheck {
        override val id = GitTrustCheck.ID
        override val name = "Git"

        override fun check(folder: Path) = findings
    }

    /** A service whose folder's trust has [check]; closed after the test. */
    private fun gated(check: ProjectTrustCheck): Pair<GitStatusService, ProjectTrust> {
        val trust = testTrust(repo, listOf(check), scripts.resolve("trust.properties"))
        val gated =
            GitStatusService(GitCli(scripts.resolve("git").toString(), System.getenv()), watcher, trust) {
                dispatched += it
            }
        closing += gated
        closing += trust
        return gated to trust
    }

    private val closing = mutableListOf<AutoCloseable>()

    @AfterEach
    fun closeGated() = closing.forEach(AutoCloseable::close)

    @Test
    fun `git status never runs while the folder's trust keeps git off`() {
        report("? a.txt")
        val (gated, _) = gated(FakeGitCheck(listOf(TrustFinding("core.fsmonitor = ./watch.sh"))))

        // Each run says so with an empty snapshot.
        gated.open(repo)
        runDispatched()
        gated.refresh()
        runDispatched()

        assertEquals(0, runs())
        assertSame(GitStatusSnapshot.EMPTY, gated.snapshot.get())
    }

    @Test
    fun `a change the window tells is checked before git runs again and before listeners hear of it`() {
        report("? a.txt")
        val check = FakeGitCheck()
        val (gated, trust) = gated(check)
        gated.open(repo)
        while (gated.snapshot.get().root == null) runDispatched()
        val before = runs()

        check.findings = listOf(TrustFinding("filter.crypt.clean = ./clean.sh"))
        gated.gitChanged()
        while (gated.snapshot.get() != GitStatusSnapshot.EMPTY) runDispatched()
        val diffs = mutableListOf<GitDiff?>()
        gated.diffAsync(repo.resolve("a.txt")) { diffs += it }
        while (diffs.isEmpty()) runDispatched()

        assertEquals(before, runs())
        assertEquals(0L, gated.gitFolderChanged.get())
        assertTrue(assertIs<ProjectTrustState.Ask>(trust.state.get()).changed)
        assertEquals(listOf<GitDiff?>(null), diffs)
    }

    @Test
    fun `a newer request for the diff of a file stops the git of the older one`() {
        report("? a.txt")
        service.open(repo)
        awaitSnapshot { it.root != null }
        val a = repo.resolve("a.txt")
        val answers = mutableListOf<String>()
        hold.createFile()
        service.diffAsync(a) { answers += "older" }
        await("git started for the older request") { runs() == 2 }

        val superseded = System.nanoTime()
        service.diffAsync(a) { answers += "newest" }

        // Unless it is stopped, the older git keeps the thread until its timeout, 3 s.
        await("git started for the newest request") { runs() == 3 }
        val waited = (System.nanoTime() - superseded).nanoseconds
        assertTrue(waited < 2.seconds, "the newest request waited $waited")
        hold.deleteExisting()
        while (answers.isEmpty()) runDispatched()
        assertEquals(listOf("newest"), answers)
    }
}
