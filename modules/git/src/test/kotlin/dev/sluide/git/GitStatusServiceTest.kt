package dev.sluide.git

import dev.sluide.filewatcher.FileWatcher
import dev.sluide.projecttrust.ProjectTrustState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
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
    private val service = GitStatusService(git, watcher, testTrust()) { dispatched += it }

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
    fun `changes in the git folder are counted for other listeners`() {
        service.open(repo)
        assertEquals(repo.toAbsolutePath().normalize(), service.root)
        awaitSnapshot { it.root != null }
        val before = service.gitFolderChanged.get()

        git("commit", "-q", "--allow-empty", "-m", "Empty")

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (service.gitFolderChanged.get() == before) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) fail("No change of the git folder was counted")
            dispatched.poll(remaining, TimeUnit.NANOSECONDS)?.run()
        }
        service.open(null)
        assertNull(service.root)
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
        git("add", ".")
        git("commit", "-q", "-m", "More files")
        repo.resolve("lines.txt").writeText("a\nB\nc\nd\ne\n")
        repo.resolve("star*.txt").writeText("two\n")
        repo.resolve("starX.txt").writeText("zero\none\n")
        assertNull(service.diff(repo.resolve("lines.txt")), "no repository followed yet")
        service.open(repo)

        assertEquals(
            GitDiff("a\nb\nc\nd\n", listOf(GitHunk(1..1, 1..1), GitHunk(4 until 4, 4..4))),
            service.diff(repo.resolve("lines.txt")),
        )
        // The name is not a pattern: starX.txt is not part of this diff.
        assertEquals(GitDiff("one\n", listOf(GitHunk(0..0, 0..0))), service.diff(repo.resolve("star*.txt")))
        assertEquals(GitDiff("val a = 1\n", emptyList()), service.diff(repo.resolve("tracked.kt")))
        assertNull(service.diff(repo.resolve("untracked.txt").apply { writeText("") }))
        assertNull(service.diff(repo.parent.resolve("outside.txt")))
        // GitCli takes relative paths from the working directory.
        val relative = Path.of("").toAbsolutePath().relativize(repo.resolve("lines.txt"))
        assertEquals("a\nb\nc\nd\n", git.headContent(repo, relative))
    }

    @Test
    fun `folders without a git folder and closing the project follow nothing`() {
        val plain = repo.resolve("plain").createDirectories()
        service.open(plain)
        assertFalse(service.active)
        assertEquals(GitStatusSnapshot.EMPTY, service.snapshot.get())

        service.open(repo)
        awaitSnapshot { it.root != null }
        service.open(null)
        assertFalse(service.active)
        assertEquals(GitStatusSnapshot.EMPTY, service.snapshot.get())
    }

    @Test
    fun `without git nothing is followed`() {
        val noGit =
            GitStatusService(GitCli(repo.resolve("no-such-git").toString(), System.getenv()), watcher, testTrust()) {}
        noGit.open(repo)
        assertFalse(noGit.active)
        noGit.close()
    }

    @Test
    fun `git stays off while the repository's configuration is not trusted`() {
        git("config", "core.fsmonitor", "./watch.sh")
        val trust = testTrust(repo, listOf(GitTrustCheck(git)))
        assertIs<ProjectTrustState.Ask>(trust.state.get())
        val gated = GitStatusService(git, watcher, trust) { dispatched += it }
        repo.resolve("new.kt").writeText("val b = 2\n")

        gated.open(repo)
        // Whether git status ran at all: GitStatusServiceRunsTest, with a git that counts its runs.
        assertEquals(GitStatusSnapshot.EMPTY, gated.snapshot.get())
        assertNull(gated.diff(repo.resolve("tracked.kt")))

        assertTrue(trust.decide(trust.state.get(), trusted = true, remember = false))
        gated.refresh()
        awaitSnapshot(gated) { it.fileStatus(repo.resolve("new.kt")) == GitStatus.UNVERSIONED }
        gated.close()
        trust.close()
    }

    @Test
    fun `a change in the git folder that makes git run commands turns git off before anything runs`() {
        val trust = testTrust(repo, listOf(GitTrustCheck(git)))
        val gated = GitStatusService(git, watcher, trust) { dispatched += it }
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
}
