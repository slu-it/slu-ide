package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.StashTip
import dev.sluide.git.cli.stashKept
import dev.sluide.git.cli.stashTip
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pulls' git parts with the real git against a bare "remote" in a temporary folder, pushed to from a second clone;
 * skipped if git is not installed.
 */
class GitPullsTest {
    @TempDir
    lateinit var folder: Path

    /** The bare repository on the "server". */
    private val remote get() = folder.resolve("remote.git")

    /** A clone that commits and (force) pushes on the "server". */
    private val other get() = folder.resolve("other")

    /** The repository that pulls, a clone of [remote]. */
    private val repo get() = folder.resolve("repo")

    private val git = GitCli("git", testGitEnvironment())
    private val pulls = GitPulls(git, GitRebases(git))

    @BeforeEach
    fun createRepositories() {
        assumeTrue(git.available, "git is not installed")
        git("init", "-q", "--bare", "-b", "main", remote.toString(), directory = folder)
        git("clone", "-q", remote.toString(), other.toString(), directory = folder)
        configure(other)
        commit(other, "Base", "file.txt")
        commit(other, "Base x", "x.txt")
        git("push", "-q", "origin", "main", directory = other)
        git("clone", "-q", remote.toString(), repo.toString(), directory = folder)
        configure(repo)
    }

    private fun configure(directory: Path) {
        git("config", "user.name", "Test", directory = directory)
        git("config", "user.email", "test@example.com", directory = directory)
    }

    private fun git(
        vararg arguments: String,
        directory: Path = repo,
    ): String {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                    environment()["GIT_EDITOR"] = ":"
                }.start()
        process.outputStream.close()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
        return output.trim()
    }

    /** Commits [file] with [content] (a line) and the [subject] in [directory]. */
    private fun commit(
        directory: Path,
        subject: String,
        file: String,
        content: String = subject,
    ) {
        directory.resolve(file).writeText("$content\n")
        git("add", file, directory = directory)
        git("commit", "-q", "-m", subject, directory = directory)
    }

    /** Commits on the "server" through [other] and pushes. */
    private fun pushed(
        subject: String,
        file: String,
        content: String = subject,
    ) {
        commit(other, subject, file, content)
        git("push", "-q", "origin", "main", directory = other)
    }

    private fun subjects() = git("log", "--format=%s", "HEAD").lines()

    private fun status() = git("status", "--porcelain").lines().map(String::trim).filter(String::isNotEmpty)

    private fun stashes() = git("stash", "list").lines().filter(String::isNotEmpty)

    private fun file(name: String) = repo.resolve(name).readText()

    @Test
    fun `the upstream is the remote-tracking branch the configuration maps to, none for a local one`() {
        git("branch", "-q", "feature", "main")
        git("branch", "-q", "local", "main")
        git("config", "branch.local.remote", ".")
        git("config", "branch.local.merge", "refs/heads/main")
        git("remote", "add", "mirror", remote.toString())
        git("config", "--replace-all", "remote.mirror.fetch", "+refs/heads/*:refs/remotes/m/*")
        git("fetch", "-q", "mirror")
        git("branch", "-q", "mapped", "main")
        git("config", "branch.mapped.remote", "mirror")
        git("config", "branch.mapped.merge", "refs/heads/main")

        fun upstreamOf(branch: String): String? {
            git("switch", "-q", branch)
            return pulls.sources(repo)?.upstream
        }

        assertEquals("refs/remotes/origin/main", upstreamOf("main"))
        assertNull(upstreamOf("feature"), "no upstream")
        assertNull(upstreamOf("local"), "a local upstream")
        assertEquals("refs/remotes/m/main", upstreamOf("mapped"), "through the remote's refspec")
    }

    @Test
    fun `the sources are the current branch, its upstream and the remote branches without symbolic refs`() {
        git("push", "-q", "origin", "main:side", directory = other)
        git("fetch", "-q", "origin")
        assertEquals(
            GitPullSources(
                branch = "main",
                hasCommit = true,
                remoteBranches = listOf("refs/remotes/origin/main", "refs/remotes/origin/side"),
                remotes = listOf("origin"),
                upstream = "refs/remotes/origin/main",
                busy = null,
            ),
            pulls.sources(repo),
        )

        git("switch", "-q", "--orphan", "fresh")
        val unborn = pulls.sources(repo)
        assertEquals("fresh", unborn?.branch)
        assertEquals(false, unborn?.hasCommit)
        assertNull(unborn?.upstream)

        git("switch", "-q", "--detach", "main")
        assertNull(pulls.sources(repo)?.branch)
    }

    @Test
    fun `what git is in the middle of is told as the reason nothing can start`() {
        git("switch", "-q", "-c", "feature")
        commit(repo, "Feature", "file.txt")
        pushed("Remote", "file.txt")
        git("fetch", "-q", "origin")
        assertIs<GitRebaseResult.Stopped>(pulls.rebase(repo, "feature", "refs/remotes/origin/main", autostash = true))

        assertEquals("A rebase is in progress; finish or abort it first.", pulls.sources(repo)?.busy)
    }

    @Test
    fun `a remote branch is there until a fetch prunes it, and the commits a rebase replayed are counted`() {
        git("push", "-q", "origin", "main:gone", directory = other)
        git("fetch", "-q", "origin")
        assertEquals(true, pulls.exists(repo, "refs/remotes/origin/gone"))
        git("push", "-q", "origin", "--delete", "gone", directory = other)
        git("fetch", "-q", "--prune", "origin")
        assertEquals(false, pulls.exists(repo, "refs/remotes/origin/gone"))
        assertEquals(false, pulls.exists(repo, "refs/remotes/origin"), "a folder of refs is no ref")

        commit(repo, "One", "one.txt")
        commit(repo, "Two", "two.txt")
        assertEquals(2, pulls.commitCount(repo, "refs/remotes/origin/main", "HEAD"))
        assertNull(pulls.commitCount(repo, "refs/remotes/origin/missing", "HEAD"))
    }

    /**
     * The remote has Old 1 and Old 2, which feature (Mine) was based on, then a force push replaces them with New;
     * both states are fetched, so the remote-tracking ref's reflog has the old one, unless [reflog] is off. Leaves
     * feature checked out.
     */
    private fun forcePushedUnder(reflog: Boolean = true) {
        if (!reflog) {
            git("config", "core.logAllRefUpdates", "false")
            repo.resolve(".git/logs/refs/remotes/origin/main").toFile().delete()
        }
        pushed("Old 1", "old1.txt")
        pushed("Old 2", "old2.txt")
        git("fetch", "-q", "origin")
        git("switch", "-q", "-c", "feature", "--no-track", "origin/main")
        commit(repo, "Mine", "mine.txt")
        git("reset", "-q", "--hard", "HEAD~2", directory = other)
        commit(other, "New", "new.txt")
        git("push", "-q", "--force", "origin", "main", directory = other)
        git("fetch", "-q", "origin")
    }

    @Test
    fun `the fork point leaves out what a force push removed from the remote, without it the old commits come back`() {
        forcePushedUnder()
        val before = git("rev-parse", "HEAD")
        val ref = "refs/remotes/origin/main"
        val forkPoint = pulls.forkPoint(repo, ref)

        assertEquals(git("rev-parse", "HEAD~1"), forkPoint, "Old 2, from the reflog")
        assertEquals(2, forkPoint?.let { pulls.commitCount(repo, ref, it) }, "Old 1 and Old 2 are left out")
        val result = pulls.rebase(repo, "feature", ref, autostash = true)

        assertIs<GitRebaseResult.Done>(result)
        assertEquals(listOf("Mine", "New", "Base x", "Base"), subjects())
        assertEquals(1, pulls.commitCount(repo, ref, "HEAD"))
        assertEquals(before, git("rev-parse", "ORIG_HEAD"), "still points to them")

        git("reset", "-q", "--hard", before)
        assertIs<GitRebaseResult.Done>(GitRebases(git).rebase(repo, "feature", ref))
        assertEquals(listOf("Mine", "Old 2", "Old 1", "New", "Base x", "Base"), subjects(), "no fork point")
    }

    @Test
    fun `without a reflog of the remote branch there is no fork point and the rebase replays every commit`() {
        forcePushedUnder(reflog = false)

        assertNull(pulls.forkPoint(repo, "refs/remotes/origin/main"))
        assertIs<GitRebaseResult.Done>(pulls.rebase(repo, "feature", "refs/remotes/origin/main", autostash = true))
        assertEquals(listOf("Mine", "Old 2", "Old 1", "New", "Base x", "Base"), subjects())
    }

    @Test
    fun `a kept stash is told only if refs_stash was read before and after and moved`() {
        assertTrue(stashKept(StashTip(null), StashTip("s1")))
        assertTrue(stashKept(StashTip("s1"), StashTip("s2")))
        assertFalse(stashKept(StashTip("s1"), StashTip("s1")))
        assertFalse(stashKept(null, StashTip("s1")), "not read before")
        assertFalse(stashKept(StashTip(null), null), "not read after")
        commit(repo, "Change", "x.txt")
        assertEquals(StashTip(null), git.stashTip(repo))
        repo.resolve("x.txt").writeText("dirty\n")
        git("stash", "-q")
        assertEquals(StashTip(git("rev-parse", "refs/stash")), git.stashTip(repo))
    }

    @Test
    fun `local changes are stashed and restored after a rebase that did not stop`() {
        git("switch", "-q", "-c", "feature")
        commit(repo, "Mine", "mine.txt")
        pushed("Remote", "remote.txt")
        git("fetch", "-q", "origin")
        repo.resolve("x.txt").writeText("dirty\n")

        val result = pulls.rebase(repo, "feature", "refs/remotes/origin/main", autostash = true)

        assertEquals(GitRebaseResult.Done(git("rev-parse", "HEAD"), stashKept = false), result)
        assertEquals(listOf("Mine", "Remote", "Base x", "Base"), subjects())
        assertEquals(listOf("M x.txt"), status())
        assertEquals(emptyList(), stashes())
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `local changes stay stashed while the rebase stops and are restored when it is continued or aborted`(
        continued: Boolean,
    ) {
        git("switch", "-q", "-c", "feature")
        commit(repo, "Mine", "file.txt")
        pushed("Remote", "file.txt")
        git("fetch", "-q", "origin")
        repo.resolve("x.txt").writeText("dirty\n")
        val rebases = GitRebases(git)

        assertIs<GitRebaseResult.Stopped>(pulls.rebase(repo, "feature", "refs/remotes/origin/main", autostash = true))
        assertEquals("Base x\n", file("x.txt"), "stashed while it stops")

        val result =
            if (continued) {
                repo.resolve("file.txt").writeText("resolved\n")
                rebases.continueRebase(repo)
            } else {
                rebases.abortRebase(repo)
            }

        assertEquals(GitRebaseResult.Done(git("rev-parse", "HEAD"), stashKept = false), result)
        assertEquals("dirty\n", file("x.txt"))
        assertEquals(listOf("M x.txt"), status())
        assertEquals(emptyList(), stashes())
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `local changes that conflict when they are restored are kept in the stash, the file has the conflict`(
        stops: Boolean,
    ) {
        git("switch", "-q", "-c", "feature")
        if (stops) commit(repo, "Mine", "file.txt") else commit(repo, "Mine", "mine.txt")
        pushed("Remote", "file.txt")
        pushed("Remote x", "x.txt")
        git("fetch", "-q", "origin")
        repo.resolve("x.txt").writeText("dirty\n")

        var result = pulls.rebase(repo, "feature", "refs/remotes/origin/main", autostash = true)
        if (stops) {
            assertIs<GitRebaseResult.Stopped>(result)
            repo.resolve("file.txt").writeText("resolved\n")
            result = GitRebases(git).continueRebase(repo)
        }

        assertEquals(GitRebaseResult.Done(git("rev-parse", "HEAD"), stashKept = true), result)
        assertEquals(listOf("stash@{0}: autostash"), stashes())
        assertEquals(listOf("UU x.txt"), status(), "unmerged, nothing in progress")
        assertContains(file("x.txt"), "<<<<<<< ")
        assertContains(file("x.txt"), "dirty")
        assertContains(file("x.txt"), "Remote x")
    }

    @Test
    fun `without the stash git refuses local changes, whatever rebase_autoStash and pull_rebase say`() {
        git("config", "rebase.autoStash", "true")
        git("config", "pull.rebase", "false")
        git("switch", "-q", "-c", "feature")
        commit(repo, "Mine", "mine.txt")
        pushed("Remote", "remote.txt")
        git("fetch", "-q", "origin")
        val before = git("rev-parse", "HEAD")
        repo.resolve("x.txt").writeText("dirty\n")

        val result = pulls.rebase(repo, "feature", "refs/remotes/origin/main", autostash = false)

        assertIs<GitRebaseResult.Failed>(result)
        assertContains(result.message, "unstaged changes")
        assertEquals(before, git("rev-parse", "HEAD"))
        assertEquals(listOf("M x.txt"), status())
        assertEquals(emptyList(), stashes())
    }

    @Test
    fun `with pull_rebase=false configured the rebase still rebases, and nothing runs when up to date`() {
        git("config", "pull.rebase", "false")
        git("switch", "-q", "-c", "feature")
        commit(repo, "Mine", "mine.txt")
        assertEquals(
            GitRebaseResult.UpToDate,
            pulls.rebase(repo, "feature", "refs/remotes/origin/main", autostash = true),
        )

        pushed("Remote", "remote.txt")
        git("fetch", "-q", "origin")
        assertIs<GitRebaseResult.Done>(pulls.rebase(repo, "feature", "refs/remotes/origin/main", autostash = true))
        assertEquals(listOf("Mine", "Remote", "Base x", "Base"), subjects())
        assertFalse(git("log", "--merges", "--format=%h").isNotEmpty(), "no merge commit")
        assertTrue(status().isEmpty())
    }
}
