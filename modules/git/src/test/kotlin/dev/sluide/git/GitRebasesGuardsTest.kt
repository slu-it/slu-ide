package dev.sluide.git

import dev.sluide.git.cli.GitCli
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a rebase does when a git command it runs fails or is refused, and which commands it runs, with the real git (or
 * behind a wrapper that fails or records); skipped if git is not installed.
 */
class GitRebasesGuardsTest {
    @TempDir
    lateinit var folder: Path

    private val repo get() = folder.resolve("repo")

    private val callLog get() = folder.resolve("calls.log")

    private val git = GitCli("git", testGitEnvironment())

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo.createDirectories()
        git("init", "-q", "-b", "main")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
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
        process.outputStream.close()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
        return output.trim()
    }

    private fun commit(
        message: String,
        file: String = "file.txt",
        content: String = message,
    ): String {
        repo.resolve(file).writeText("$content\n")
        git("add", file)
        git("commit", "-q", "-m", message)
        return git("rev-parse", "HEAD")
    }

    /** main: Base, Main (changes file.txt); feature from Base: A (changes file.txt, so it conflicts), B. feature is out. */
    private fun diverged() {
        commit("Base")
        git("switch", "-q", "-c", "feature")
        commit("A", content = "A")
        commit("B", "b.txt")
        git("switch", "-q", "main")
        commit("Main")
        git("switch", "-q", "feature")
    }

    private fun rebase(rebases: GitRebases) = rebases.rebase(repo, "feature", "refs/heads/main")

    private fun subject() = git("log", "-1", "--format=%s")

    private fun remoteRef(
        remote: String,
        name: String,
    ) = git("update-ref", "refs/remotes/$remote/$name", "HEAD")

    @Test
    fun `a git that cannot tell what is in progress refuses to start, continue, skip or abort`() {
        diverged()
        val refused = GitRebaseResult.Failed(IN_PROGRESS_UNREADABLE)
        val failing = { GitRebases(failingGit(folder, "--git-path")) }

        assertEquals(refused, rebase(failing()))
        assertEquals(refused, failing().continueRebase(repo))
        assertEquals(refused, failing().skipRebase(repo))
        assertEquals(refused, failing().abortRebase(repo))
        assertEquals("B", subject())
    }

    @Test
    fun `a rebase whose containment cannot be read fails and runs no rebase`() {
        diverged()

        assertEquals(
            GitRebaseResult.Failed("Whether 'feature' already contains refs/heads/main could not be read."),
            rebase(GitRebases(failingGit(folder, "merge-base"))),
        )
        assertEquals("B", subject())
    }

    @Test
    fun `a rebase that stopped is a failure with git's message when what is in progress cannot be read afterwards`() {
        diverged()

        // `--git-path` is read once before the rebase (the guard) and once after it; only the second fails.
        val result = rebase(GitRebases(failingGit(folder, "--git-path", after = 1)))

        val failed = assertIs<GitRebaseResult.Failed>(result)
        assertTrue("Rebasing (1/2)" in failed.message, failed.message)
    }

    @Test
    fun `aborting a rebase is told done when what is in progress cannot be read afterwards`() {
        diverged()
        assertIs<GitRebaseResult.Stopped>(rebase(GitRebases(git)))
        val before = git("rev-parse", "feature")

        val result = GitRebases(failingGit(folder, "--git-path", after = 1)).abortRebase(repo)

        assertEquals(GitRebaseResult.Done(before), result)
    }

    @Test
    fun `continuing fails with the reason when the conflicts cannot be read, checked or staged`() {
        diverged()
        assertIs<GitRebaseResult.Stopped>(rebase(GitRebases(git)))
        repo.resolve("file.txt").writeText("resolved\n")
        val unreadable = GitRebaseResult.Failed(CONFLICTS_UNREADABLE)

        // The first read of `--unmerged` finds what is in progress, the one after it lists the unmerged entries.
        assertEquals(unreadable, GitRebases(failingGit(folder, "--unmerged", after = 1)).continueRebase(repo))
        assertEquals(unreadable, GitRebases(failingGit(folder, "check-attr")).continueRebase(repo))
        assertEquals(
            GitRebaseResult.Failed("fatal: refused by test"),
            GitRebases(failingGit(folder, "update-index")).continueRebase(repo),
        )
        assertIs<GitPendingRebase>(GitLog(git).inProgress(repo))
    }

    @Test
    fun `continuing refuses a folder in place of a conflicted file with the reason to stage nothing`() {
        diverged()
        assertIs<GitRebaseResult.Stopped>(rebase(GitRebases(git)))
        Files.delete(repo.resolve("file.txt"))
        repo.resolve("file.txt").createDirectories()

        val result = GitRebases(git).continueRebase(repo)

        assertEquals(
            GitRebaseResult.Failed(
                "file.txt is not a regular file (a folder or a special file), which git cannot stage. " +
                    "Resolve it in the terminal.",
            ),
            result,
        )
    }

    @ParameterizedTest
    @CsvSource("diff-index,0", "--name-only,0", "--unmerged,2")
    fun `an empty resolution is not skipped when the index, the unmerged paths or the changes cannot be read`(
        word: String,
        after: Int,
    ) {
        diverged()
        val failing = runCatching { git("rebase", "--apply", "main") }
        assertTrue(failing.isFailure)
        git("checkout", "--ours", "file.txt")

        // With the apply backend git refuses to continue an empty resolution: not skipping leaves the rebase stopped.
        // `--unmerged` is read four times: twice finding what is in progress, once for the unmerged entries, and then
        // by `nothingToCommit` (after the staging), whose read the count of 2 fails: git cannot tell the step by its
        // arguments. The read after the run fails too, so the rebase is unreadable afterwards: failed, not stopped.
        val result = GitRebases(failingGit(folder, word, after = after)).continueRebase(repo)

        assertFalse(result is GitRebaseResult.Done, result.toString())
    }

    @Test
    fun `an empty resolution is skipped when everything can be read`() {
        diverged()
        val failing = runCatching { git("rebase", "--apply", "main") }
        assertTrue(failing.isFailure)
        git("checkout", "--ours", "file.txt")

        assertIs<GitRebaseResult.Done>(GitRebases(git).continueRebase(repo))
    }

    @Test
    fun `the conflict check reads files by the JVM unless the hook says it cannot name them`() {
        diverged()
        val rebases = GitRebases(recordingGit(folder, callLog))
        assertIs<GitRebaseResult.Stopped>(rebase(rebases))
        repo.resolve("file.txt").writeText("<<<<<<< ours\n")

        assertEquals(GitRebaseResult.MarkersLeft(listOf("file.txt")), rebases.continueRebase(repo))
        assertFalse(recordedCalls(callLog).any { "hash-object" in it }, "the JVM names the file")

        rebases.canNameFile = { false }
        assertEquals(GitRebaseResult.MarkersLeft(listOf("file.txt")), rebases.continueRebase(repo))
        assertTrue(recordedCalls(callLog).any { "hash-object" in it }, "git stores the file")
    }

    @Test
    fun `the merge commits are counted for a name git can take only, and not when git fails`() {
        commit("Base")
        git("switch", "-q", "-c", "feature")
        commit("A", "a.txt")
        git("switch", "-q", "main")
        commit("Main", "main.txt")
        git("switch", "-q", "feature")
        git("merge", "-q", "--no-ff", "-m", "Merge main", "main")
        val rebases = GitRebases(recordingGit(folder, callLog))

        assertNull(rebases.mergeCount(repo, "-x"))
        assertNull(rebases.mergeCount(repo, ""))
        assertEquals(emptyList(), recordedCalls(callLog), "git is not run for a name it cannot take")
        assertNull(GitRebases(failingGit(folder, "rev-list")).mergeCount(repo, "refs/heads/main"))
        assertEquals(1, rebases.mergeCount(repo, "refs/heads/main"))
    }

    @Test
    fun `the default branch of a remote is none when git cannot list the refs`() {
        commit("Base")
        remoteRef("origin", "main")

        assertNull(GitRebases(failingGit(folder, "for-each-ref")).remoteDefaultBranch(repo, "origin"))
        assertEquals("refs/remotes/origin/main", GitRebases(git).remoteDefaultBranch(repo, "origin"))
    }

    @Test
    fun `a default branch is a ref of the remote that exists and is no symbolic ref, main before master in any case`() {
        commit("Base")
        remoteRef("origin", "master")
        remoteRef("origin", "Main")
        remoteRef("upstream", "main")

        assertEquals("refs/remotes/origin/Main", GitRebases(git).remoteDefaultBranch(repo, "origin"))

        // A HEAD that points to another remote's branch is not this remote's default branch.
        git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/upstream/main")
        assertNull(GitRebases(git).remoteDefaultBranch(repo, "origin"))
        git("symbolic-ref", "--delete", "refs/remotes/origin/HEAD")

        // A main that stands for the master is no branch of its own.
        git("update-ref", "-d", "refs/remotes/origin/Main")
        git("symbolic-ref", "refs/remotes/origin/main", "refs/remotes/origin/master")
        assertEquals("refs/remotes/origin/master", GitRebases(git).remoteDefaultBranch(repo, "origin"))
    }

    @Test
    fun `the names of the default branch are matched ignoring case, main before master`() {
        commit("Base")
        remoteRef("origin", "main")
        remoteRef("origin", "MASTER")

        assertEquals("refs/remotes/origin/main", GitRebases(git).remoteDefaultBranch(repo, "origin"))
    }

    @Test
    fun `local changes that git cannot apply after a skip are kept in the stash`() {
        commit("Base")
        commit("Other", "other.txt", "1\n2\n3")
        git("switch", "-q", "-c", "feature")
        commit("A", content = "A")
        git("switch", "-q", "main")
        repo.resolve("file.txt").writeText("Main\n")
        repo.resolve("other.txt").writeText("main\n2\n3\n")
        git("add", "-A")
        git("commit", "-q", "-m", "Main")
        git("switch", "-q", "feature")
        repo.resolve("other.txt").writeText("local\n2\n3\n")
        val rebases = GitRebases(git)
        assertIs<GitRebaseResult.Stopped>(rebases.rebase(repo, "feature", "refs/heads/main", autostash = true))

        val result = rebases.skipRebase(repo)

        val done = assertIs<GitRebaseResult.Done>(result)
        assertTrue(done.stashKept, "the changes were kept as a stash entry")
        assertEquals(1, git("stash", "list").lines().size)
    }
}
