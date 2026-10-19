package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.inProgress
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
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

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo.createDirectories()
        git("init", "-q", "-b", "main")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
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
                }.start()
        process.outputStream.close()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
        return output.trim()
    }

    /** Commits [file] with [content] (a line) and [message]; returns the commit's hash. */
    private fun commit(
        message: String,
        file: String = "file.txt",
        content: String = message,
    ): String {
        repo.resolve(file).writeText("$content\n")
        git("add", file)
        git("commit", "-q", "-m", message)
        return head()
    }

    private fun head() = git("rev-parse", "HEAD")

    private fun subjects(range: String = "HEAD") = git("log", "--format=%s", range).lines()

    /**
     * main: Base, Main (changes file.txt); feature from Base: A (changes file.txt, so it conflicts), then B (adds
     * b.txt, or changes file.txt too if [twoConflicts]). Leaves feature checked out; returns A's hash.
     */
    private fun diverged(twoConflicts: Boolean = false): String {
        commit("Base")
        git("switch", "-q", "-c", "feature")
        val a = commit("A\n\nBody of A.", content = "A")
        if (twoConflicts) commit("B", content = "B") else commit("B", "b.txt")
        git("switch", "-q", "main")
        commit("Main")
        git("switch", "-q", "feature")
        return a
    }

    private fun main() = git("rev-parse", "main")

    @Test
    fun `rebases the current branch onto another`() {
        commit("Base")
        git("switch", "-q", "-c", "feature")
        commit("A", "a.txt")
        git("switch", "-q", "main")
        commit("Main", "main.txt")
        git("switch", "-q", "feature")

        val result = rebases.rebase(repo, "feature", "refs/heads/main")

        assertEquals(GitRebaseResult.Done(head()), result)
        assertEquals(listOf("A", "Main", "Base"), subjects())
        assertEquals("refs/heads/feature", git("symbolic-ref", "HEAD"))
        assertNull(git.inProgress(repo).getOrThrow())
    }

    @Test
    fun `a branch behind the other is fast-forwarded`() {
        commit("Base")
        git("switch", "-q", "-c", "feature")
        git("switch", "-q", "main")
        commit("Main", "main.txt")
        git("switch", "-q", "feature")

        assertEquals(GitRebaseResult.Done(main()), rebases.rebase(repo, "feature", "refs/heads/main"))
        assertEquals(main(), head())
    }

    @Test
    fun `nothing runs when the other branch is already contained, also merged in`() {
        commit("Base")
        git("switch", "-q", "-c", "feature")
        commit("A", "a.txt")
        git("switch", "-q", "main")
        commit("Main", "main.txt")
        git("switch", "-q", "feature")
        git("merge", "-q", "--no-ff", "-m", "Merge main", "main")
        val before = head()

        assertEquals(GitRebaseResult.UpToDate, rebases.rebase(repo, "feature", "refs/heads/main"))
        assertEquals(before, head(), "the merge is not linearised")
        assertEquals(1, rebases.mergeCount(repo, "refs/heads/main"), "a rebase would drop the merge")
    }

    @Test
    fun `counts the merge commits the rebase would drop, also with rebase_rebaseMerges configured`() {
        git("config", "rebase.rebaseMerges", "true")
        commit("Base")
        git("switch", "-q", "-c", "side")
        commit("Side", "side.txt")
        git("switch", "-q", "-c", "feature", "main")
        commit("A", "a.txt")
        git("merge", "-q", "--no-ff", "-m", "Merge side", "side")
        git("switch", "-q", "main")
        commit("Main", "main.txt")
        git("switch", "-q", "feature")

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
        git("config", "rebase.autoStash", "true")
        git("config", "rebase.updateRefs", "true")
        commit("Base")
        git("switch", "-q", "-c", "feature")
        val part = commit("A", "a.txt")
        git("branch", "part")
        commit("B", "b.txt")
        git("switch", "-q", "main")
        commit("Main", "main.txt")
        git("switch", "-q", "feature")
        repo.resolve("a.txt").writeText("local\n")

        assertIs<GitRebaseResult.Failed>(rebases.rebase(repo, "feature", "refs/heads/main"))
        assertEquals("", git("stash", "list"), "nothing stashed")
        assertEquals("local\n", repo.resolve("a.txt").readText())

        git("checkout", "a.txt")
        assertIs<GitRebaseResult.Done>(rebases.rebase(repo, "feature", "refs/heads/main"))
        assertEquals(part, git("rev-parse", "part"), "the other branch did not move")
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
        git("rebase", "--abort")

        val merging = runCatching { git("merge", "main") }
        assertTrue(merging.isFailure)
        assertEquals(
            GitRebaseResult.Failed("A merge is in progress; finish or abort it first."),
            rebases.rebase(repo, "feature", "refs/heads/main"),
        )
        assertEquals(
            GitRebaseResult.Failed("A merge is in progress; finish or abort it first."),
            rebases.continueRebase(repo),
        )
        git("merge", "--abort")
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
        assertEquals("A\n\nBody of A.", git("log", "-1", "--format=%B", "HEAD~1"))
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
        git("checkout", "--ours", "file.txt")

        assertIs<GitRebaseResult.Done>(rebases.continueRebase(repo))
        assertEquals(listOf("B", "Main", "Base"), subjects())
        assertEquals("Main\n", repo.resolve("file.txt").readText())
    }

    @Test
    fun `git 2_53 drops a commit left empty by its resolution on continue too`() {
        // Pins why skipping and continuing agree for the merge backend; the apply backend refuses (next test).
        diverged()
        rebases.rebase(repo, "feature", "refs/heads/main")
        git("checkout", "--ours", "file.txt")
        git("add", "file.txt")

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
        assertEquals("refs/heads/feature", git("symbolic-ref", "HEAD"))
    }

    @Test
    fun `a rebase started in a terminal with the apply backend is read and continued`() {
        val a = diverged()
        val failing = runCatching { git("rebase", "--apply", "main") }
        assertTrue(failing.isFailure)

        assertEquals(
            GitPendingRebase("feature", main(), a, 1, 2, listOf("file.txt")),
            git.inProgress(repo).getOrThrow(),
        )
        repo.resolve("file.txt").writeText("resolved\n")
        assertIs<GitRebaseResult.Done>(rebases.continueRebase(repo))
        assertEquals(listOf("B", "A", "Main", "Base"), subjects())
        assertEquals("A\n\nBody of A.", git("log", "-1", "--format=%B", "HEAD~1"))
    }

    @Test
    fun `an empty resolution of an apply rebase, which git would refuse to continue, is skipped`() {
        diverged()
        val failing = runCatching { git("rebase", "--apply", "main") }
        assertTrue(failing.isFailure)
        git("checkout", "--ours", "file.txt")

        assertIs<GitRebaseResult.Done>(rebases.continueRebase(repo))
        assertEquals(listOf("B", "Main", "Base"), subjects())
    }

    @Test
    fun `an empty resolution is not skipped while files have unstaged changes, which the skip would drop`() {
        commit("Base")
        commit("Other", "other.txt")
        git("switch", "-q", "-c", "feature")
        commit("A")
        commit("B", "b.txt")
        git("switch", "-q", "main")
        commit("Main")
        git("switch", "-q", "feature")
        val failing = runCatching { git("rebase", "--apply", "main") }
        assertTrue(failing.isFailure)
        git("checkout", "--ours", "file.txt")
        repo.resolve("other.txt").writeText("local\n")

        val result = rebases.continueRebase(repo)

        assertIs<GitRebaseResult.Stopped>(result)
        assertEquals(1, result.pending.step)
        assertEquals("local\n", repo.resolve("other.txt").readText())
    }

    @Test
    fun `a stop without conflicts is continued, not skipped, so git tells there is nothing to commit`() {
        commit("Base")
        git("switch", "-q", "-c", "feature")
        val a = commit("A", "new.txt")
        git("rm", "-q", "new.txt")
        git("commit", "-q", "-m", "B")
        git("switch", "-q", "main")
        commit("Main", "main.txt")
        git("switch", "-q", "feature")
        repo.resolve("new.txt").writeText("untracked\n")
        val failing = runCatching { git("rebase", "--apply", "main") }
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
        commit("Base")
        repo.resolve("bin.dat").writeBytes(byteArrayOf(0, 1))
        git("add", "bin.dat")
        git("commit", "-q", "-m", "Binary")
        git("switch", "-q", "-c", "feature")
        repo.resolve("bin.dat").writeBytes(byteArrayOf(0, 2))
        git("commit", "-q", "-am", "A")
        git("switch", "-q", "main")
        repo.resolve("bin.dat").writeBytes(byteArrayOf(0, 3))
        git("commit", "-q", "-am", "Main")
        git("switch", "-q", "feature")
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
        git("switch", "-q", "--detach", "feature")
        val failing = runCatching { git("rebase", "main") }
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
        patch.writeText(git("format-patch", "-1", "--stdout", a) + "\n")
        git("switch", "-q", "main")
        val failing = runCatching { git("am", patch.toString()) }
        assertTrue(failing.isFailure)

        assertEquals(GitPendingOperation(GitOperation.AM), git.inProgress(repo).getOrThrow())
        assertEquals(
            GitRebaseResult.Failed("A git am session is in progress; finish or abort it first."),
            rebases.continueRebase(repo),
        )
        assertTrue(repo.resolve(".git/rebase-apply").exists())
    }
}
