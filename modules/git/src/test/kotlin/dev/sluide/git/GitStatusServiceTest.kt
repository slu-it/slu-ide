package dev.sluide.git

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.filewatcher.FileWatcher
import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.headContent
import dev.sluide.projecttrust.ProjectTrustState
import dev.sluide.textediting.TextEncoding
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/** Runs the real git in a temporary repository; skipped if git is not installed. */
class GitStatusServiceTest {
    @TempDir
    lateinit var repo: Path

    private val git = GitCli("git", testGitEnvironment())

    // Everything dispatched runs on this test's thread, in run(), like on the JavaFX thread.
    private val dispatched = LinkedBlockingQueue<Runnable>()
    private val watcher = FileWatcher({ dispatched += it }, 50.milliseconds)

    // The threads tasks are dispatched from.
    private val dispatchers = mutableListOf<String>()
    private val service =
        GitStatusService(git, watcher, testTrust(), BackgroundTasks.forTests()) {
            synchronized(dispatchers) { dispatchers += Thread.currentThread().name }
            dispatched += it
        }

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        git("init", "-q")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
        repo.resolve("tracked.kt").writeText("val a = 1\n")
        repo.resolve(".gitignore").writeText("build/\n*.log\n")
        git("add", ".")
        git("commit", "-q", "-m", "Initial commit")
    }

    @AfterEach
    fun close() {
        service.close()
        watcher.close()
    }

    private fun git(vararg arguments: String): String {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                }.start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
        return output.trim()
    }

    /** Runs dispatched tasks until [condition] holds. */
    private fun awaitSnapshot(
        service: GitStatusService = this.service,
        condition: (GitStatusSnapshot) -> Boolean,
    ): GitStatusSnapshot {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition(service.snapshot.get())) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) fail("Timed out; last snapshot: ${service.snapshot.get()}")
            dispatched.poll(remaining, TimeUnit.NANOSECONDS)?.run()
        }
        return service.snapshot.get()
    }

    /** Runs dispatched tasks until [condition] holds. */
    private fun await(
        message: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) fail(message)
            dispatched.poll(remaining, TimeUnit.NANOSECONDS)?.run()
        }
    }

    /** The answer of [GitStatusService.diffAsync] for [file], which arrives through dispatch, not at once. */
    private fun diffOf(
        file: Path,
        service: GitStatusService = this.service,
    ): GitDiff? {
        val answers = mutableListOf<GitDiff?>()
        service.diffAsync(file) { answers += it }
        assertEquals(emptyList(), answers, "not answered at once")
        await("No diff of $file") { answers.isNotEmpty() }
        return answers.single()
    }

    @Test
    fun `reports unversioned, added, modified and ignored files of the opened repository`() {
        repo.resolve("tracked.kt").writeText("val a = 2\n")
        repo.resolve("new.kt").writeText("")
        git("add", "new.kt")
        repo.resolve("scratch.kt").writeText("")
        repo.resolve("debug.log").writeText("")
        repo.resolve("build/libs").createDirectories()
        repo.resolve("build/libs/app.jar").writeText("")

        service.open(repo)
        assertTrue(service.active)
        val snapshot = awaitSnapshot { it.root != null }

        assertEquals(GitStatus.MODIFIED, snapshot.fileStatus(repo.resolve("tracked.kt")))
        assertEquals(GitStatus.ADDED, snapshot.fileStatus(repo.resolve("new.kt")))
        assertEquals(GitStatus.UNVERSIONED, snapshot.fileStatus(repo.resolve("scratch.kt")))
        assertEquals(GitStatus.IGNORED, snapshot.fileStatus(repo.resolve("debug.log")))
        assertEquals(GitStatus.IGNORED, snapshot.fileStatus(repo.resolve("build/libs/app.jar")))
        assertEquals(setOf(GitStatus.IGNORED), snapshot.folderStatuses(repo.resolve("build")))
        assertEquals(
            listOf(GitStatus.UNVERSIONED, GitStatus.ADDED, GitStatus.MODIFIED),
            snapshot.folderStatuses(repo).toList(),
        )
    }

    @Test
    fun `changes in the git folder update the status by themselves`() {
        repo.resolve("new.kt").writeText("")
        service.open(repo)
        awaitSnapshot { it.fileStatus(repo.resolve("new.kt")) == GitStatus.UNVERSIONED }

        git("add", "new.kt")
        awaitSnapshot { it.fileStatus(repo.resolve("new.kt")) == GitStatus.ADDED }

        git("commit", "-q", "-m", "Add new.kt")
        awaitSnapshot { it.fileStatus(repo.resolve("new.kt")) == null }
    }

    @Test
    fun `switching branches and checking out commits updates the head by themselves`() {
        git("branch", "-M", "main")
        service.open(repo)
        assertEquals("main", awaitSnapshot { it.head != null }.head?.branch)

        git("checkout", "-q", "--detach")
        val commit = git("rev-parse", "HEAD").lines().last()
        val detached = awaitSnapshot { it.head?.branch == null && it.head?.commit != null }
        assertEquals(GitHead(null, commit), detached.head)

        git("switch", "-q", "-c", "other")
        assertEquals(GitHead("other", commit), awaitSnapshot { it.head?.branch == "other" }.head)
    }

    @Test
    fun `refresh picks up changes in the working tree`() {
        service.open(repo)
        awaitSnapshot { it.root != null }

        repo.resolve("tracked.kt").writeText("val a = 3\n")
        service.refresh()
        awaitSnapshot { it.fileStatus(repo.resolve("tracked.kt")) == GitStatus.MODIFIED }
    }

    @Test
    fun `diff gives the content at HEAD and the changed lines`() {
        repo.resolve("lines.txt").writeText("a\nb\nc\nd\n")
        repo.resolve("star*.txt").writeText("one\n")
        repo.resolve("starX.txt").writeText("one\n")
        val windows1252 = TextEncoding.WINDOWS_1252.charset
        repo.resolve("latin1.txt").writeBytes("Grüße\n".toByteArray(windows1252))
        git("add", ".")
        git("commit", "-q", "-m", "More files")
        repo.resolve("latin1.txt").writeBytes("Grüße!\n".toByteArray(windows1252))
        repo.resolve("lines.txt").writeText("a\nB\nc\nd\ne\n")
        repo.resolve("star*.txt").writeText("two\n")
        repo.resolve("starX.txt").writeText("zero\none\n")
        assertNull(diffOf(repo.resolve("lines.txt")), "no repository followed yet")
        service.open(repo)

        assertEquals(
            GitDiff("a\nb\nc\nd\n", listOf(GitHunk(1..1, 1..1), GitHunk(4 until 4, 4..4))),
            diffOf(repo.resolve("lines.txt")),
        )
        synchronized(dispatchers) { assertTrue("sluide-git-diff" in dispatchers, "dispatched by $dispatchers") }
        // The name is not a pattern: starX.txt is not part of this diff.
        assertEquals(GitDiff("one\n", listOf(GitHunk(0..0, 0..0))), diffOf(repo.resolve("star*.txt")))
        assertEquals(GitDiff("val a = 1\n", emptyList()), diffOf(repo.resolve("tracked.kt")))
        // Decoded as the editor decodes the file.
        assertEquals(GitDiff("Grüße\n", listOf(GitHunk(0..0, 0..0))), diffOf(repo.resolve("latin1.txt")))
        assertNull(diffOf(repo.resolve("untracked.txt").apply { writeText("") }))
        assertNull(diffOf(repo.parent.resolve("outside.txt")))
        // GitCli takes relative paths from the working directory.
        val relative = Path.of("").toAbsolutePath().relativize(repo.resolve("lines.txt"))
        assertEquals("a\nb\nc\nd\n", git.headContent(repo, relative)?.decodeToString())
    }

    @Test
    fun `only the newest request for the diff of a file is answered`() {
        repo.resolve("tracked.kt").writeText("val a = 2\n")
        service.open(repo)
        val answers = mutableListOf<Pair<String, GitDiff?>>()

        service.diffAsync(repo.resolve("tracked.kt")) { answers += "older" to it }
        service.diffAsync(repo.resolve("other.kt")) { answers += "other" to it }
        service.diffAsync(repo.resolve("tracked.kt")) { answers += "newest" to it }
        // Answers are dispatched in order: one for the older request would come before the newest.
        await("No answers") { answers.size >= 2 }

        assertEquals(
            listOf("other" to null, "newest" to GitDiff("val a = 1\n", listOf(GitHunk(0..0, 0..0)))),
            answers,
        )
    }

    @Test
    fun `a result that arrives after a newer request is dropped`() {
        // Nothing followed, so only diffs are dispatched, and the test decides when they arrive.
        val answersDispatched = LinkedBlockingQueue<Runnable>()
        val unopened =
            GitStatusService(git, watcher, testTrust(), BackgroundTasks.forTests()) { answersDispatched += it }
        val file = repo.resolve("tracked.kt")
        val answers = mutableListOf<String>()
        unopened.diffAsync(file) { answers += "older" }
        val older = answersDispatched.poll(10, TimeUnit.SECONDS) ?: fail("No result")

        unopened.diffAsync(file) { answers += "newest" }
        older.run()
        (answersDispatched.poll(10, TimeUnit.SECONDS) ?: fail("No result")).run()

        assertEquals(listOf("newest"), answers)
        unopened.close()
    }

    @Test
    fun `a diff of a repository no longer followed is dropped`() {
        repo.resolve("tracked.kt").writeText("val a = 2\n")
        service.open(repo)
        val answers = mutableListOf<String>()
        service.diffAsync(repo.resolve("tracked.kt")) { answers += "of the repository" }

        service.open(null)
        // Diffs are answered in order: the older one would come before this one.
        service.diffAsync(repo.resolve("other.kt")) { answers += "after" }
        await("No answer") { answers.isNotEmpty() }

        assertEquals(listOf("after"), answers)
    }

    @Test
    fun `folders without a git folder and closing the project follow nothing`() {
        val plain = repo.resolve("plain").createDirectories()
        service.open(plain)
        assertFalse(service.active)
        assertEquals(GitStatusSnapshot.EMPTY, service.snapshot.get())

        service.open(repo)
        assertEquals(repo.toAbsolutePath().normalize(), service.root)
        awaitSnapshot { it.root != null }
        service.open(null)
        assertNull(service.root)
        assertFalse(service.active)
        assertEquals(GitStatusSnapshot.EMPTY, service.snapshot.get())
    }

    @Test
    fun `without git nothing is followed`() {
        val noGit =
            GitStatusService(
                GitCli(repo.resolve("no-such-git").toString(), System.getenv()),
                watcher,
                testTrust(),
                BackgroundTasks.forTests(),
            ) {
            }
        noGit.open(repo)
        assertFalse(noGit.active)
        noGit.close()
    }

    @Test
    fun `git stays off while the repository's configuration is not trusted`() {
        git("config", "core.fsmonitor", "./watch.sh")
        val trust = testTrust(repo, listOf(GitTrustCheck(git)))
        assertIs<ProjectTrustState.Ask>(trust.state.get())
        val gated = GitStatusService(git, watcher, trust, BackgroundTasks.forTests()) { dispatched += it }
        repo.resolve("new.kt").writeText("val b = 2\n")

        gated.open(repo)
        // Whether git status ran at all: GitStatusServiceRunsTest, with a git that counts its runs.
        assertEquals(GitStatusSnapshot.EMPTY, gated.snapshot.get())
        assertNull(diffOf(repo.resolve("tracked.kt"), gated))

        assertTrue(trust.decide(trust.state.get(), trusted = true, remember = false))
        gated.refresh()
        awaitSnapshot(gated) { it.fileStatus(repo.resolve("new.kt")) == GitStatus.UNVERSIONED }
        gated.close()
        trust.close()
    }

    @Test
    fun `a change in the git folder that makes git run commands turns git off before anything runs`() {
        val trust = testTrust(repo, listOf(GitTrustCheck(git)))
        val gated = GitStatusService(git, watcher, trust, BackgroundTasks.forTests()) { dispatched += it }
        gated.open(repo)
        awaitSnapshot(gated) { it.root != null }
        val before = gated.gitFolderChanged.get()

        git("config", "filter.crypt.clean", "./clean.sh")

        awaitSnapshot(gated) { it == GitStatusSnapshot.EMPTY }
        assertTrue(assertIs<ProjectTrustState.Ask>(trust.state.get()).changed)
        assertEquals(before, gated.gitFolderChanged.get(), "listeners would run git with the new configuration")

        assertTrue(trust.decide(trust.state.get(), trusted = true, remember = false))
        gated.refresh()
        awaitSnapshot(gated) { it.root != null }
        git("commit", "-q", "--allow-empty", "-m", "Empty")
        await("A change of the trusted git folder is counted") { gated.gitFolderChanged.get() != before }
        gated.close()
        trust.close()
    }

    @Test
    fun `a hook made executable turns git off with the next change of git's folder`() {
        val realCheck = GitTrustCheck(git)
        val trust = testTrust(repo, listOf(realCheck))
        val gated =
            GitStatusService(git, watcher, trust, BackgroundTasks.forTests(), realCheck::inputs) { dispatched += it }
        val hook = repo.resolve(".git/hooks/post-commit").apply { writeText("#!/bin/sh\nexit 0\n") }
        hook.toFile().setExecutable(false)
        gated.open(repo)
        awaitSnapshot(gated) { it.root != null }
        // The first change after opening checks again whatever changed; from then on the stamps tell.
        gated.gitChanged()
        await("the told change") { gated.gitFolderChanged.get() == 1L }

        hook.toFile().setExecutable(true)
        // Hooks are below .git, which the watch does not follow; a lock file of git's directly in it is seen.
        repo.resolve(".git/index.lock").writeText("")

        awaitSnapshot(gated) { it == GitStatusSnapshot.EMPTY }
        assertTrue(assertIs<ProjectTrustState.Ask>(trust.state.get()).changed)
        gated.close()
        trust.close()
    }

    @Test
    fun `every git status is a background task`() {
        val lines = CopyOnWriteArrayList<String>()
        val recorded =
            GitStatusService(git, watcher, testTrust(), BackgroundTasks.forTests(lines::add)) { dispatched += it }

        recorded.open(repo)
        awaitSnapshot(recorded) { it.root != null }
        recorded.close()

        val numbered = lines.map { it.replace(Regex("task#\\d+"), "task") }
        assertEquals("task started: Refreshing Git status", numbered.first())
        assertTrue(numbered[1].startsWith("task finished in "), numbered[1])
        assertTrue(numbered[1].endsWith(": Refreshing Git status"), numbered[1])
    }
}
