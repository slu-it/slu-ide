package dev.sluide.git

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.git.cli.GitCli
import dev.sluide.projecttrust.ProjectTrust
import dev.sluide.projecttrust.ProjectTrustCheck
import dev.sluide.projecttrust.TrustFinding
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/**
 * Which changes of `.git` make [GitStatusService] run git's trust check, count [GitStatusService.gitFolderChanged] and
 * run `git status`, and how writes hold them (ADR 0062). A script stands in for git: it logs each run and waits while
 * the hold file exists.
 */
class GitStatusServiceCascadeTest {
    @TempDir
    lateinit var repo: Path

    @TempDir
    lateinit var scripts: Path

    private val gitFolder get() = repo.resolve(".git")
    private val runLog get() = scripts.resolve("runs")
    private val hold get() = scripts.resolve("hold")

    // Everything dispatched runs on this test's thread, like on the JavaFX thread.
    private val dispatched = LinkedBlockingQueue<Runnable>()
    private val watcher = FileWatcher({ dispatched += it }, 50.milliseconds)
    private val lines = CopyOnWriteArrayList<String>()
    private val warnings = CopyOnWriteArrayList<String>()
    private val previousSink = GitStatusLog.sink
    private val previousWarningSink = GitStatusLog.warningSink

    /** A check standing in for git's that counts how often it runs. */
    private class CountingCheck : ProjectTrustCheck {
        val runs = AtomicInteger()

        /** What it finds; the test may change it. */
        @Volatile var findings: List<TrustFinding> = emptyList()
        override val id = GitTrustCheck.ID
        override val name = "Git"

        override fun check(folder: Path): List<TrustFinding> {
            runs.incrementAndGet()
            return findings
        }
    }

    private val check = CountingCheck()
    private lateinit var trust: ProjectTrust
    private lateinit var service: GitStatusService

    @BeforeEach
    fun createService() {
        gitFolder.resolve("refs/heads").createDirectories()
        gitFolder.resolve("hooks").createDirectories()
        gitFolder.resolve("HEAD").writeText("ref: refs/heads/main\n")
        gitFolder.resolve("config").writeText("[core]\n")
        val executable =
            scripts.resolve("git").apply {
                writeText(
                    FAKE_GIT_START +
                        """
                        echo run >> '$runLog'
                        while [ -e '$hold' ]; do sleep 0.01; done
                        printf '? a.txt\0'
                        """.trimIndent() + "\n",
                )
                toFile().setExecutable(true)
            }
        val git = GitCli(executable.toString(), System.getenv())
        assumeTrue(git.available, "no /bin/sh to stand in for git")
        GitStatusLog.sink = { lines += it }
        GitStatusLog.warningSink = { warnings += it }
        trust = testTrust(repo, listOf(check), scripts.resolve("trust.properties"))
        val inputs =
            GitTrustInputs(setOf(gitFolder.resolve("config")), setOf(gitFolder.resolve("hooks")), emptySet())
        service =
            GitStatusService(git, watcher, trust, BackgroundTasks.forTests(), { inputs }) { dispatched += it }
        service.open(repo)
        awaitFor("the first status") { service.snapshot.get().root != null }
        // The first change after opening runs the check again (its stamp is not known yet); this one is told.
        service.gitChanged()
        awaitFor("the told change") { service.gitFolderChanged.get() == 1L }
        awaitFor("the told change's status") { cycleEnds() == 2 }
    }

    @AfterEach
    fun close() {
        GitStatusLog.sink = previousSink
        GitStatusLog.warningSink = previousWarningSink
        service.close()
        trust.close()
        watcher.close()
    }

    private fun runs() = if (runLog.exists()) runLog.readLines().count { it == "run" } else 0

    /** The cycles that ran `git status` to its end. */
    private fun cycleEnds() = lines.count { Regex("Git status cycle for .*, status \\d+ ms, ").containsMatchIn(it) }

    private fun skipped() = lines.count { it.endsWith(": .git unchanged, nothing runs") }

    /** Runs dispatched tasks until [condition] holds. */
    private fun awaitFor(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for $what; log: $lines")
            dispatched.poll(10, TimeUnit.MILLISECONDS)?.run()
        }
    }

    /** What the cycles did since [before]: trust checks, counted changes, status runs. */
    private data class Counts(
        val checks: Int,
        val changes: Long,
        val runs: Int,
    )

    private fun counts() = Counts(check.runs.get(), service.gitFolderChanged.get(), runs())

    @Test
    fun `changes of objects and reflogs only run nothing`() {
        val before = counts()

        gitFolder
            .resolve("objects/ab")
            .createDirectories()
            .resolve("cdef")
            .writeText("blob")
        gitFolder
            .resolve("logs")
            .createDirectories()
            .resolve("HEAD")
            .writeText("reflog")

        awaitFor("the skipped batch") { skipped() >= 1 }
        assertEquals(before, counts())
    }

    @Test
    fun `a change of the refs is counted without checking the trust again`() {
        val before = counts()

        gitFolder.resolve("packed-refs").writeText("1234 refs/heads/x\n")

        awaitFor("the status of the change") { cycleEnds() >= 3 }
        assertEquals(Counts(before.checks, before.changes + 1, before.runs + 1), counts())
    }

    @Test
    fun `a new branch below refs is counted with the next batch`() {
        val before = counts()

        gitFolder.resolve("refs/heads/x").writeText("1234\n")
        // Not seen by the watch, which follows entries directly in .git only; a lock file of git's is.
        gitFolder.resolve("index.lock").createFile()

        awaitFor("the status of the change") { cycleEnds() >= 3 }
        assertEquals(Counts(before.checks, before.changes + 1, before.runs + 1), counts())
    }

    @Test
    fun `a change of the configuration checks the trust again`() {
        val before = counts()

        gitFolder.resolve("config").writeText("[core]\n\tfsmonitor = ./watch.sh\n")

        awaitFor("the status of the change") { cycleEnds() >= 3 }
        assertEquals(before.checks + 1, check.runs.get())
    }

    @Test
    fun `a change of a hook checks the trust again without counting a change`() {
        val before = counts()

        gitFolder.resolve("hooks/pre-commit").writeText("#!/bin/sh\n")
        gitFolder.resolve("index.lock").createFile()

        awaitFor("the status of the change") { cycleEnds() >= 3 }
        assertEquals(Counts(before.checks + 1, before.changes, before.runs + 1), counts())
    }

    @Test
    fun `the batch of the watch after a told change runs nothing more`() {
        val before = counts()
        hold.createFile()

        gitFolder.resolve("HEAD").writeText("ref: refs/heads/other\n")
        service.gitChanged()
        awaitFor("the status of the told change") { runs() == before.runs + 1 }
        // The watch's batch comes after its quiet period of 50 ms; its line is logged on the thread git holds.
        val quiet = System.nanoTime() + 300_000_000
        awaitFor("the batch of the watch") { System.nanoTime() > quiet }
        hold.deleteExisting()
        awaitFor("the batch of the watch logged") { lines.count { it.startsWith("Changes in .git of ") } == 1 }

        awaitFor("the skipped batch") { skipped() >= 1 }
        awaitFor("the told change counted") { service.gitFolderChanged.get() == before.changes + 1 }
        assertEquals(Counts(before.checks + 1, before.changes + 1, before.runs + 1), counts())
    }

    private fun waited() = lines.count { it.endsWith(" waits for a git write") }

    @Test
    fun `what a write changes and the refreshes meanwhile run once at its end`() {
        val before = counts()

        service.writeStarted(holdStatus = true)
        gitFolder.resolve("HEAD").writeText("ref: refs/heads/other\n")
        service.refresh()
        awaitFor("the batch of the watch") { lines.count { it.startsWith("Changes in .git of ") } == 1 }
        awaitFor("a cycle that waits") { waited() == 1 }
        Thread.sleep(100)
        while (dispatched.isNotEmpty()) dispatched.poll()?.run()
        assertEquals(before, counts(), "nothing ran while the write ran")

        service.writeFinished()
        awaitFor("the status at the write's end") { cycleEnds() >= 3 }
        Thread.sleep(100)
        while (dispatched.isNotEmpty()) dispatched.poll()?.run()
        assertEquals(Counts(before.checks + 1, before.changes + 1, before.runs + 1), counts())
        assertEquals(1, waited(), "logged once per write")
    }

    @Test
    fun `a write that does not hold the status lets refreshes run but counts its changes at its end`() {
        val before = counts()

        service.writeStarted(holdStatus = false)
        gitFolder.resolve("packed-refs").writeText("1234 refs/remotes/origin/main\n")
        awaitFor("a cycle that waits") { waited() == 1 }
        service.refresh()
        awaitFor("the refresh") { cycleEnds() >= 3 }
        assertEquals(before.changes, service.gitFolderChanged.get(), "not counted while the write runs")

        service.writeFinished()
        awaitFor("the change counted at the write's end") { service.gitFolderChanged.get() == before.changes + 1 }
    }

    @Test
    fun `a write whose end is never told is let go with a warning`() {
        val before = counts()
        service.holdLimit = 300.milliseconds

        service.writeStarted(holdStatus = true)
        service.refresh()
        awaitFor("a cycle that waits") { waited() == 1 }

        awaitFor("the status after the limit") { runs() == before.runs + 1 }
        assertEquals(1, warnings.size, "$warnings")
        assertTrue("held the status of $repo for more than " in warnings.single(), warnings.single())
    }

    /** The answer of [GitStatusService.diffAsync] for `a.txt`: with the fake git, a diff whenever git runs. */
    private fun diffAnswer(): GitDiff? {
        val answers = mutableListOf<GitDiff?>()
        service.diffAsync(repo.resolve("a.txt")) { answers += it }
        awaitFor("the diff") { answers.isNotEmpty() }
        return answers.single()
    }

    @Test
    fun `the editor's diffs keep working during a write while what the trust check reads is unchanged`() {
        assertNotNull(diffAnswer(), "before")
        service.writeStarted(holdStatus = false)

        gitFolder.resolve("packed-refs").writeText("1234 refs/remotes/origin/main\n")
        awaitFor("a cycle that waits") { waited() == 1 }
        assertNotNull(diffAnswer(), "a change of refs during a fetch")

        gitFolder.resolve("config").writeText("[core]\n\tfsmonitor = ./watch.sh\n")
        awaitFor("the waiting cycle's stamp") {
            lines.any { it.endsWith("trust inputs changed, diffs wait for the git write") }
        }
        assertNull(diffAnswer(), "a change of the configuration waits for the check at the write's end")

        service.writeFinished()
        awaitFor("the cycle at the write's end") { cycleEnds() >= 3 }
        assertNotNull(diffAnswer(), "after the check")
    }

    @Test
    fun `a check that turned git off runs again with the next change`() {
        val before = counts()
        check.findings = listOf(TrustFinding("core.fsmonitor = ./watch.sh"))
        gitFolder.resolve("config").writeText("[core]\n\tfsmonitor = ./watch.sh\n")
        awaitFor("the check of the change") { check.runs.get() == before.checks + 1 }

        gitFolder.resolve("packed-refs").writeText("1234 refs/heads/x\n")

        awaitFor("the check of the next change") { check.runs.get() == before.checks + 2 }

        // Back as it was when git was last allowed: still checked, as git is off.
        check.findings = emptyList()
        gitFolder.resolve("config").writeText("[core]\n")
        awaitFor("the check of the reverted configuration") { check.runs.get() == before.checks + 3 }
        awaitFor("git on again") { service.snapshot.get().root != null }
    }
}
