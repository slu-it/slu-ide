package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.TestRepository
import dev.sluide.git.cli.inProgress
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the real git in temporary repositories; skipped if git is not installed. */
class GitRebasesTest {
    @TempDir
    lateinit var folder: Path

    private val repo get() = folder.resolve("repo")

    private val git = GitCli("git", testGitEnvironment())
    private val rebases = GitRebases(git)
    private lateinit var repository: TestRepository

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repository = TestRepository(repo)
    }

    private fun head() = repository.git("rev-parse", "HEAD")

    private fun subjects(range: String = "HEAD") = repository.git("log", "--format=%s", range).lines()

    /**
     * main: Base, Main (changes file.txt); feature from Base: A (changes file.txt, so it conflicts), then B (adds
     * b.txt, or changes file.txt too if [twoConflicts]). Leaves feature checked out; returns A's hash.
     */
    private fun diverged(twoConflicts: Boolean = false): String {
        repository.commit("Base")
        repository.git("switch", "-q", "-c", "feature")
        val a = repository.commit("A\n\nBody of A.", content = "A")
        if (twoConflicts) repository.commit("B", content = "B") else repository.commit("B", "b.txt")
        repository.git("switch", "-q", "main")
        repository.commit("Main")
        repository.git("switch", "-q", "feature")
        return a
    }

    private fun main() = repository.git("rev-parse", "main")

    @Test
    fun `rebases the current branch onto another`() {
        repository.commit("Base")
        repository.git("switch", "-q", "-c", "feature")
        repository.commit("A", "a.txt")
        repository.git("switch", "-q", "main")
        repository.commit("Main", "main.txt")
        repository.git("switch", "-q", "feature")

        val result = rebases.rebase(repo, "feature", "refs/heads/main")

        assertEquals(GitRebaseResult.Done(head()), result)
        assertEquals(listOf("A", "Main", "Base"), subjects())
        assertEquals("refs/heads/feature", repository.git("symbolic-ref", "HEAD"))
        assertNull(git.inProgress(repo).getOrThrow())
    }

    @Test
    fun `a branch behind the other is fast-forwarded`() {
        repository.commit("Base")
        repository.git("switch", "-q", "-c", "feature")
        repository.git("switch", "-q", "main")
        repository.commit("Main", "main.txt")
        repository.git("switch", "-q", "feature")

        assertEquals(GitRebaseResult.Done(main()), rebases.rebase(repo, "feature", "refs/heads/main"))
        assertEquals(main(), head())
    }

    @Test
    fun `nothing runs when the other branch is already contained, also merged in`() {
        repository.commit("Base")
        repository.git("switch", "-q", "-c", "feature")
        repository.commit("A", "a.txt")
        repository.git("switch", "-q", "main")
        repository.commit("Main", "main.txt")
        repository.git("switch", "-q", "feature")
        repository.git("merge", "-q", "--no-ff", "-m", "Merge main", "main")
        val before = head()

        assertEquals(GitRebaseResult.UpToDate, rebases.rebase(repo, "feature", "refs/heads/main"))
        assertEquals(before, head(), "the merge is not linearised")
        assertEquals(1, rebases.mergeCount(repo, "refs/heads/main"), "a rebase would drop the merge")
    }

    @Test
    fun `counts the merge commits the rebase would drop, also with rebase_rebaseMerges configured`() {
        repository.git("config", "rebase.rebaseMerges", "true")
        repository.commit("Base")
        repository.git("switch", "-q", "-c", "side")
        repository.commit("Side", "side.txt")
        repository.git("switch", "-q", "-c", "feature", "main")
        repository.commit("A", "a.txt")
        repository.git("merge", "-q", "--no-ff", "-m", "Merge side", "side")
        repository.git("switch", "-q", "main")
        repository.commit("Main", "main.txt")
        repository.git("switch", "-q", "feature")

        assertEquals(1, rebases.mergeCount(repo, "refs/heads/main"))
        assertEquals(0, rebases.mergeCount(repo, "refs/heads/feature"))
        assertNull(rebases.mergeCount(repo, "-x"))

        assertIs<GitRebaseResult.Done>(rebases.rebase(repo, "feature", "refs/heads/main"))
        assertEquals(listOf("Side", "A", "Main", "Base"), subjects(), "the merge is dropped, its changes replayed")
        assertEquals(0, rebases.mergeCount(repo, "refs/heads/main"))
    }

    @Test
    fun `local changes are refused by git and leave nothing behind`() {
        diverged()
        val before = head()
        repo.resolve("file.txt").writeText("local\n")

        val result = rebases.rebase(repo, "feature", "refs/heads/main")

        assertIs<GitRebaseResult.Failed>(result)
        assertTrue("unstaged changes" in result.message, result.message)
        assertEquals(before, head())
        assertNull(git.inProgress(repo).getOrThrow())
        assertEquals("local\n", repo.resolve("file.txt").readText())
    }

    @Test
    fun `rebase_autoStash and rebase_updateRefs of the configuration are not applied`() {
        repository.git("config", "rebase.autoStash", "true")
        repository.git("config", "rebase.updateRefs", "true")
        repository.commit("Base")
        repository.git("switch", "-q", "-c", "feature")
        val part = repository.commit("A", "a.txt")
        repository.git("branch", "part")
        repository.commit("B", "b.txt")
        repository.git("switch", "-q", "main")
        repository.commit("Main", "main.txt")
        repository.git("switch", "-q", "feature")
        repo.resolve("a.txt").writeText("local\n")

        assertIs<GitRebaseResult.Failed>(rebases.rebase(repo, "feature", "refs/heads/main"))
        assertEquals("", repository.git("stash", "list"), "nothing stashed")
        assertEquals("local\n", repo.resolve("a.txt").readText())

        repository.git("checkout", "a.txt")
        assertIs<GitRebaseResult.Done>(rebases.rebase(repo, "feature", "refs/heads/main"))
        assertEquals(part, repository.git("rev-parse", "part"), "the other branch did not move")
    }

    @Test
    fun `a conflict stops the rebase, which is read with its state`() {
        val a = diverged()
        val main = main()

        val result = rebases.rebase(repo, "feature", "refs/heads/main")

        assertIs<GitRebaseResult.Stopped>(result)
        val expected = GitPendingRebase("feature", main, a, 1, 2, listOf("file.txt"))
        assertEquals(expected, result.pending)
        assertEquals(expected, git.inProgress(repo).getOrThrow())
    }

    @Test
    fun `nothing starts while git is in the middle of something or the branch is no longer current`() {
        diverged()
        assertEquals(
            GitRebaseResult.Failed("'other' is no longer the current branch."),
            rebases.rebase(repo, "other", "refs/heads/main"),
        )
        assertIs<GitRebaseResult.Stopped>(rebases.rebase(repo, "feature", "refs/heads/main"))
        val busy = GitRebaseResult.Failed("A rebase is in progress; finish or abort it first.")
        assertEquals(busy, rebases.rebase(repo, "feature", "refs/heads/main"))
        repository.git("rebase", "--abort")

        val merging = runCatching { repository.git("merge", "main") }
        assertTrue(merging.isFailure)
        assertEquals(
            GitRebaseResult.Failed("A merge is in progress; finish or abort it first."),
            rebases.rebase(repo, "feature", "refs/heads/main"),
        )
        assertEquals(
            GitRebaseResult.Failed("A merge is in progress; finish or abort it first."),
            rebases.continueRebase(repo),
        )
        repository.git("merge", "--abort")
        assertEquals(GitRebaseResult.Failed("No rebase is in progress."), rebases.continueRebase(repo))
        assertEquals(GitRebaseResult.Failed("No rebase is in progress."), rebases.skipRebase(repo))
        assertEquals(GitRebaseResult.Failed("No rebase is in progress."), rebases.abortRebase(repo))
    }

    @Test
    fun `continuing is refused while conflict markers are left`() {
        diverged()
        rebases.rebase(repo, "feature", "refs/heads/main")

        assertEquals(GitRebaseResult.MarkersLeft(listOf("file.txt")), rebases.continueRebase(repo))
        assertIs<GitPendingRebase>(git.inProgress(repo).getOrThrow())
    }

    @Test
    fun `continuing commits the resolution with the original message and finishes the rebase`() {
        diverged()
        rebases.rebase(repo, "feature", "refs/heads/main")
        repo.resolve("file.txt").writeText("resolved\n")

        val result = rebases.continueRebase(repo)

        assertEquals(GitRebaseResult.Done(head()), result)
        assertEquals(listOf("B", "A", "Main", "Base"), subjects())
        assertEquals("A\n\nBody of A.", repository.git("log", "-1", "--format=%B", "HEAD~1"))
        assertEquals("resolved\n", repo.resolve("file.txt").readText())
        assertNull(git.inProgress(repo).getOrThrow())
    }

    @Test
    fun `a second conflict stops again, the next continue finishes`() {
        diverged(twoConflicts = true)
        rebases.rebase(repo, "feature", "refs/heads/main")
        repo.resolve("file.txt").writeText("resolved A\n")

        val stopped = rebases.continueRebase(repo)

        assertIs<GitRebaseResult.Stopped>(stopped)
        assertEquals(2, stopped.pending.step)
        assertEquals(listOf("file.txt"), stopped.pending.conflicts)
        repo.resolve("file.txt").writeText("resolved B\n")
        assertIs<GitRebaseResult.Done>(rebases.continueRebase(repo))
        assertEquals(listOf("B", "A", "Main", "Base"), subjects())
    }

    @Test
    fun `a resolution that leaves nothing to commit skips the commit`() {
        diverged()
        rebases.rebase(repo, "feature", "refs/heads/main")
        repository.git("checkout", "--ours", "file.txt")

        assertIs<GitRebaseResult.Done>(rebases.continueRebase(repo))
        assertEquals(listOf("B", "Main", "Base"), subjects())
        assertEquals("Main\n", repo.resolve("file.txt").readText())
    }

    @Test
    fun `git 2_53 drops a commit left empty by its resolution on continue too`() {
        // Pins why skipping and continuing agree for the merge backend; the apply backend refuses (next test).
        diverged()
        rebases.rebase(repo, "feature", "refs/heads/main")
        repository.git("checkout", "--ours", "file.txt")
        repository.git("add", "file.txt")

        val (result, _) =
            git.writeWithExit(repo, listOf("rebase", "--continue"), environment = mapOf("GIT_EDITOR" to ":"))

        assertTrue(result.success, result.message)
        assertEquals(listOf("B", "Main", "Base"), subjects())
    }

    @Test
    fun `skips the commit the rebase stopped at`() {
        diverged()
        rebases.rebase(repo, "feature", "refs/heads/main")

        val result = rebases.skipRebase(repo)

        assertEquals(GitRebaseResult.Done(head()), result)
        assertEquals(listOf("B", "Main", "Base"), subjects())
    }

    @Test
    fun `aborting takes the branch back to where it was`() {
        diverged()
        val before = head()
        rebases.rebase(repo, "feature", "refs/heads/main")

        assertEquals(GitRebaseResult.Done(before), rebases.abortRebase(repo))
        assertEquals(before, head())
        assertEquals("A\n", repo.resolve("file.txt").readText())
        assertEquals("refs/heads/feature", repository.git("symbolic-ref", "HEAD"))
    }

    @Test
    fun `a rebase started in a terminal with the apply backend is read and continued`() {
        val a = diverged()
        val failing = runCatching { repository.git("rebase", "--apply", "main") }
        assertTrue(failing.isFailure)

        assertEquals(
            GitPendingRebase("feature", main(), a, 1, 2, listOf("file.txt")),
            git.inProgress(repo).getOrThrow(),
        )
        repo.resolve("file.txt").writeText("resolved\n")
        assertIs<GitRebaseResult.Done>(rebases.continueRebase(repo))
        assertEquals(listOf("B", "A", "Main", "Base"), subjects())
        assertEquals("A\n\nBody of A.", repository.git("log", "-1", "--format=%B", "HEAD~1"))
    }

    @Test
    fun `an empty resolution of an apply rebase, which git would refuse to continue, is skipped`() {
        diverged()
        val failing = runCatching { repository.git("rebase", "--apply", "main") }
        assertTrue(failing.isFailure)
        repository.git("checkout", "--ours", "file.txt")

        assertIs<GitRebaseResult.Done>(rebases.continueRebase(repo))
        assertEquals(listOf("B", "Main", "Base"), subjects())
    }

    @Test
    fun `an empty resolution is not skipped while files have unstaged changes, which the skip would drop`() {
        repository.commit("Base")
        repository.commit("Other", "other.txt")
        repository.git("switch", "-q", "-c", "feature")
        repository.commit("A")
        repository.commit("B", "b.txt")
        repository.git("switch", "-q", "main")
        repository.commit("Main")
        repository.git("switch", "-q", "feature")
        val failing = runCatching { repository.git("rebase", "--apply", "main") }
        assertTrue(failing.isFailure)
        repository.git("checkout", "--ours", "file.txt")
        repo.resolve("other.txt").writeText("local\n")

        val result = rebases.continueRebase(repo)

        assertIs<GitRebaseResult.Stopped>(result)
        assertEquals(1, result.pending.step)
        assertEquals("local\n", repo.resolve("other.txt").readText())
    }

    @Test
    fun `a stop without conflicts is continued, not skipped, so git tells there is nothing to commit`() {
        repository.commit("Base")
        repository.git("switch", "-q", "-c", "feature")
        val a = repository.commit("A", "new.txt")
        repository.git("rm", "-q", "new.txt")
        repository.git("commit", "-q", "-m", "B")
        repository.git("switch", "-q", "main")
        repository.commit("Main", "main.txt")
        repository.git("switch", "-q", "feature")
        repo.resolve("new.txt").writeText("untracked\n")
        val failing = runCatching { repository.git("rebase", "--apply", "main") }
        assertTrue(failing.isFailure, "the untracked file is in the way")
        repo.resolve("new.txt").deleteExisting()

        val result = rebases.continueRebase(repo)

        assertIs<GitRebaseResult.Stopped>(result, "A is not dropped silently")
        assertEquals(a, result.pending.stopped)
        assertEquals(1, result.pending.step)
        assertEquals(listOf("Main", "Base"), subjects())
    }

    @Test
    fun `continuing commits files that cannot be checked only once confirmed`() {
        repository.commit("Base")
        repo.resolve("bin.dat").writeBytes(byteArrayOf(0, 1))
        repository.git("add", "bin.dat")
        repository.git("commit", "-q", "-m", "Binary")
        repository.git("switch", "-q", "-c", "feature")
        repo.resolve("bin.dat").writeBytes(byteArrayOf(0, 2))
        repository.git("commit", "-q", "-am", "A")
        repository.git("switch", "-q", "main")
        repo.resolve("bin.dat").writeBytes(byteArrayOf(0, 3))
        repository.git("commit", "-q", "-am", "Main")
        repository.git("switch", "-q", "feature")
        assertIs<GitRebaseResult.Stopped>(rebases.rebase(repo, "feature", "refs/heads/main"))
        repo.resolve("bin.dat").writeBytes(byteArrayOf(0, 4))
        val unchecked = GitUncheckedConflict("bin.dat", GitConflictReason.BINARY)

        assertEquals(GitRebaseResult.Unchecked(listOf(unchecked)), rebases.continueRebase(repo))
        assertIs<GitPendingRebase>(git.inProgress(repo).getOrThrow())

        assertIs<GitRebaseResult.Done>(rebases.continueRebase(repo, setOf(unchecked)))
        assertEquals(listOf("A", "Main", "Binary", "Base"), subjects())
        assertTrue(repo.resolve("bin.dat").readBytes().contentEquals(byteArrayOf(0, 4)))
    }

    @Test
    fun `an option is never passed as the branch to rebase onto`() {
        diverged()

        assertEquals(
            GitRebaseResult.Failed("'--exec=touch x' cannot be passed to git: it starts with '-'."),
            rebases.rebase(repo, "feature", "--exec=touch x"),
        )
        assertNull(git.inProgress(repo).getOrThrow())
    }

    @Test
    fun `a rebase of a detached HEAD started in a terminal is read without a branch`() {
        val a = diverged()
        repository.git("switch", "-q", "--detach", "feature")
        val failing = runCatching { repository.git("rebase", "main") }
        assertTrue(failing.isFailure)

        assertEquals(
            GitPendingRebase(null, main(), a, 1, 2, listOf("file.txt")),
            git.inProgress(repo).getOrThrow(),
        )
        assertIs<GitRebaseResult.Done>(rebases.abortRebase(repo))
    }

    @Test
    fun `git am stays an operation for the terminal`() {
        val a = diverged()
        val patch = folder.resolve("a.patch")
        patch.writeText(repository.git("format-patch", "-1", "--stdout", a) + "\n")
        repository.git("switch", "-q", "main")
        val failing = runCatching { repository.git("am", patch.toString()) }
        assertTrue(failing.isFailure)

        assertEquals(GitPendingOperation(GitOperation.AM), git.inProgress(repo).getOrThrow())
        assertEquals(
            GitRebaseResult.Failed("A git am session is in progress; finish or abort it first."),
            rebases.continueRebase(repo),
        )
        assertTrue(repo.resolve(".git/rebase-apply").exists())
    }

    private fun remoteRef(
        remote: String,
        name: String,
    ) = repository.git("update-ref", "refs/remotes/$remote/$name", "HEAD")

    @Test
    fun `the default branch of a remote is what its HEAD points at, even with a main`() {
        repository.commit("Base")
        remoteRef("origin", "main")
        remoteRef("origin", "develop")
        repository.git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/develop")

        assertEquals("refs/remotes/origin/develop", rebases.remoteDefaultBranch(repo, "origin"))
    }

    @Test
    fun `without a remote HEAD the default branch is main, else master, else none`() {
        repository.commit("Base")
        remoteRef("origin", "master")
        assertEquals("refs/remotes/origin/master", rebases.remoteDefaultBranch(repo, "origin"))

        remoteRef("origin", "main")
        assertEquals("refs/remotes/origin/main", rebases.remoteDefaultBranch(repo, "origin"))
    }

    @Test
    fun `a remote without a default branch gives none, another remote's main is not taken`() {
        repository.commit("Base")
        remoteRef("origin", "develop")
        remoteRef("upstream", "main")

        assertNull(rebases.remoteDefaultBranch(repo, "origin"))
        assertEquals("refs/remotes/upstream/main", rebases.remoteDefaultBranch(repo, "upstream"))
    }

    @Test
    fun `a remote HEAD whose branch is gone is skipped for main`() {
        repository.commit("Base")
        remoteRef("origin", "main")
        remoteRef("origin", "master")
        repository.git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/master")
        repository.git("update-ref", "-d", "refs/remotes/origin/master")

        assertEquals("refs/remotes/origin/main", rebases.remoteDefaultBranch(repo, "origin"))
    }

    @Test
    fun `local changes git cannot apply after a skip are kept in the stash`() {
        repository.commit("Base")
        repository.commit("Other", "other.txt", "1\n2\n3")
        repository.git("switch", "-q", "-c", "feature")
        repository.commit("A", content = "A")
        repository.git("switch", "-q", "main")
        repo.resolve("file.txt").writeText("Main\n")
        repo.resolve("other.txt").writeText("main\n2\n3\n")
        repository.git("add", "-A")
        repository.git("commit", "-q", "-m", "Main")
        repository.git("switch", "-q", "feature")
        repo.resolve("other.txt").writeText("local\n2\n3\n")
        assertIs<GitRebaseResult.Stopped>(rebases.rebase(repo, "feature", "refs/heads/main", autostash = true))

        val result = rebases.skipRebase(repo)

        val done = assertIs<GitRebaseResult.Done>(result)
        assertTrue(done.stashKept, "the changes were kept as a stash entry")
        assertEquals(1, repository.git("stash", "list").lines().size)
    }

    @Test
    fun `local changes git cannot apply when the rebase is aborted are kept in the stash`() {
        diverged()
        // A new file in the index, which git stashes for the rebase.
        repo.resolve("n.txt").writeText("staged\n")
        repository.git("add", "n.txt")
        assertIs<GitRebaseResult.Stopped>(rebases.rebase(repo, "feature", "refs/heads/main", autostash = true))
        // The user creates the file again while the rebase is stopped.
        repo.resolve("n.txt").writeText("untracked meanwhile\n")

        val result = rebases.abortRebase(repo)

        // git: "could not apply autostash; your changes are safe in the stash" and exit 0.
        val done = assertIs<GitRebaseResult.Done>(result)
        assertTrue(done.stashKept, "the changes were kept as a stash entry")
        assertEquals(1, repository.git("stash", "list").lines().size)
    }
}
