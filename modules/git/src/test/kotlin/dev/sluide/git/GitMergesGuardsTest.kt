package dev.sluide.git

import dev.sluide.git.cli.GitCli
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What a merge does when a git command it runs fails or is refused, and which commands it runs, with the real git (or
 * behind a wrapper that fails or records); skipped if git is not installed.
 */
class GitMergesGuardsTest {
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
                    environment()["GIT_EDITOR"] = ":"
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

    /** main: Base, Main (changes file.txt if [conflict], else adds main.txt); feature: Base, A. feature checked out. */
    private fun diverged(conflict: Boolean = false) {
        commit("Base")
        git("switch", "-q", "-c", "feature")
        commit("A")
        git("switch", "-q", "main")
        if (conflict) commit("Main") else commit("Main", "main.txt")
        git("switch", "-q", "feature")
    }

    private fun merge(
        merges: GitMerges,
        mode: GitMergeMode = GitMergeMode.MERGE_COMMIT,
        message: String? = null,
        autostash: Boolean = true,
        ref: String = "refs/heads/main",
    ) = merges.merge(repo, "feature", ref, mode, message, autostash)

    private fun message() = git("log", "-1", "--format=%B")

    private fun recording() = GitMerges(recordingGit(folder, callLog))

    /** The recorded `git merge` call (git's arguments of the one line that runs it). */
    private fun mergeCall() = recordedCalls(callLog).single { "merge" in it.split(' ') && "--no-edit" in it }

    @Test
    fun `a git that cannot tell what is in progress refuses to start, continue or abort`() {
        diverged()
        val refused = GitMergeResult.Failed(IN_PROGRESS_UNREADABLE)

        assertEquals(refused, merge(GitMerges(failingGit(folder, "--git-path"))))
        assertEquals(refused, GitMerges(failingGit(folder, "--git-path")).continueMerge(repo))
        assertEquals(refused, GitMerges(failingGit(folder, "--git-path")).abortMerge(repo))
        assertEquals("A", git("log", "-1", "--format=%s"))
    }

    @Test
    fun `a merge whose containment or commit cannot be read fails and runs no merge`() {
        diverged()

        assertEquals(
            GitMergeResult.Failed("Whether 'feature' already contains refs/heads/main could not be read."),
            merge(GitMerges(failingGit(folder, "merge-base"))),
        )
        assertEquals(
            GitMergeResult.Failed("refs/heads/main could not be read."),
            merge(GitMerges(failingGit(folder, "--verify"))),
        )
        assertEquals("A", git("log", "-1", "--format=%s"))
    }

    @Test
    fun `a merge that stopped is a failure with git's message when what is in progress cannot be read afterwards`() {
        diverged(conflict = true)

        // The first read (before the merge) works, the second one (after it) fails.
        val result = merge(GitMerges(failingGit(folder, "--git-path", after = 1)))

        val failed = assertIs<GitMergeResult.Failed>(result)
        assertTrue("CONFLICT" in failed.message, failed.message)
    }

    @Test
    fun `aborting or continuing a merge is told done when what is in progress cannot be read afterwards`() {
        diverged(conflict = true)
        assertIs<GitMergeResult.Stopped>(merge(GitMerges(git)))
        val before = git("rev-parse", "HEAD")

        assertEquals(
            GitMergeResult.Done(before),
            GitMerges(failingGit(folder, "--git-path", after = 1)).abortMerge(repo),
        )

        assertIs<GitMergeResult.Stopped>(merge(GitMerges(git)))
        repo.resolve("file.txt").writeText("resolved\n")
        val continued = GitMerges(failingGit(folder, "--git-path", after = 1)).continueMerge(repo)
        assertIs<GitMergeResult.Done>(continued)
        assertEquals("resolved\n", repo.resolve("file.txt").toFile().readText())
    }

    @Test
    fun `continuing fails with the reason when the conflicts cannot be read, checked or staged`() {
        diverged(conflict = true)
        assertIs<GitMergeResult.Stopped>(merge(GitMerges(git)))
        repo.resolve("file.txt").writeText("resolved\n")
        val unreadable = GitMergeResult.Failed(CONFLICTS_UNREADABLE)

        assertEquals(unreadable, GitMerges(failingGit(folder, "--unmerged", after = 1)).continueMerge(repo))
        assertEquals(unreadable, GitMerges(failingGit(folder, "check-attr")).continueMerge(repo))
        val staging = GitMerges(failingGit(folder, "update-index")).continueMerge(repo)
        assertEquals(GitMergeResult.Failed("fatal: refused by test"), staging)
        assertIs<GitPendingMerge>(GitLog(git).inProgress(repo))
    }

    @Test
    fun `continuing refuses a folder in place of a conflicted file with git's reason to stage nothing`() {
        diverged(conflict = true)
        assertIs<GitMergeResult.Stopped>(merge(GitMerges(git)))
        Files.delete(repo.resolve("file.txt"))
        repo.resolve("file.txt").createDirectories()

        val result = GitMerges(git).continueMerge(repo)

        assertEquals(
            GitMergeResult.Failed(
                "file.txt is not a regular file (a folder or a special file), which git cannot stage. " +
                    "Resolve it in the terminal.",
            ),
            result,
        )
        assertIs<GitPendingMerge>(GitLog(git).inProgress(repo))
    }

    @Test
    fun `the conflict check reads files by the JVM unless the hook says it cannot name them`() {
        diverged(conflict = true)
        val merges = recording()
        assertIs<GitMergeResult.Stopped>(merge(merges))
        repo.resolve("file.txt").writeText("<<<<<<< ours\n")

        assertEquals(GitMergeResult.MarkersLeft(listOf("file.txt")), merges.continueMerge(repo))
        assertFalse(recordedCalls(callLog).any { "hash-object" in it }, "the JVM names the file")

        merges.canNameFile = { false }
        assertEquals(GitMergeResult.MarkersLeft(listOf("file.txt")), merges.continueMerge(repo))
        assertTrue(recordedCalls(callLog).any { "hash-object" in it }, "git stores the file")
    }

    @Test
    fun `merges by the short name of a branch only, a message file only with a message, and cleans it up`() {
        diverged()
        val merges = recording()

        assertIs<GitMergeResult.Done>(merge(merges, GitMergeMode.MERGE_COMMIT))
        val plain = mergeCall()

        assertEquals("merge --no-ff --autostash --no-edit --end-of-options main", plain)

        git("reset", "-q", "--hard", "HEAD^")
        Files.deleteIfExists(callLog)
        assertIs<GitMergeResult.Done>(merge(merges, GitMergeMode.MERGE_COMMIT, message = "Written by hand"))
        val withMessage = mergeCall()

        val pattern =
            Regex(
                "-c i18n.commitEncoding=UTF-8 merge --no-ff --autostash --no-edit --cleanup=whitespace " +
                    "--file=(.+) --end-of-options main",
            )
        val file = pattern.matchEntire(withMessage)?.groupValues?.get(1) ?: error(withMessage)
        assertFalse(Path.of(file).exists(), "the message file is deleted")
        assertEquals("Written by hand", message())
    }

    @Test
    fun `a name that is no branch is merged by that name with the default message of a branch`() {
        diverged()
        git("update-ref", "ORIG_HEAD", "main")
        val merges = recording()

        assertIs<GitMergeResult.Done>(merge(merges, GitMergeMode.MERGE_COMMIT, ref = "ORIG_HEAD"))

        assertTrue(mergeCall().endsWith("--end-of-options ORIG_HEAD"), mergeCall())
        // Not "Merge commit 'ORIG_HEAD'" as git would say it: the default message of a branch.
        assertEquals("Merge branch 'ORIG_HEAD' into feature", message())
    }

    @Test
    fun `a squash with local changes in the way fails with git's message`() {
        diverged()
        commit("More", "main.txt")
        git("switch", "-q", "main")
        commit("Main again", "main.txt")
        git("switch", "-q", "feature")
        repo.resolve("main.txt").writeText("local\n")

        // Git refuses with exit 1, and there are no conflicts: no squash was done.
        val refused = merge(GitMerges(git), GitMergeMode.SQUASH)

        val failed = assertIs<GitMergeResult.Failed>(refused)
        assertTrue("would be overwritten" in failed.message, failed.message)
    }

    @Test
    fun `a squash whose conflicts cannot be listed is no squash`() {
        diverged(conflict = true)
        val head = git("rev-parse", "HEAD")

        // Git exits with 1 and leaves conflicts; the failing read after it (the third of the unmerged paths) lists none.
        val result = merge(GitMerges(failingGit(folder, "--unmerged", after = 2)), GitMergeMode.SQUASH)

        val failed = assertIs<GitMergeResult.Failed>(result)
        assertTrue(failed.message.startsWith("Auto-merging file.txt"), failed.message)
        assertTrue("CONFLICT (content): Merge conflict in file.txt" in failed.message, failed.message)
        assertEquals(head, git("rev-parse", "HEAD"))
    }

    @Test
    fun `a squash is told done with the stash kept when something stashed during it`() {
        diverged()
        // git runs this hook after a squash too; it stashes the staged changes of the squash.
        val hook = repo.resolve(".git/hooks/post-merge")
        hook.parent.createDirectories()
        hook.writeText("#!/bin/sh\ngit stash push -q\n")
        hook.toFile().setExecutable(true)

        val result = merge(GitMerges(git), GitMergeMode.SQUASH)

        assertEquals(GitMergeResult.Done(git("rev-parse", "HEAD"), squashed = true, stashKept = true), result)
        assertEquals(1, git("stash", "list").lines().size)
    }

    /** main changes file.txt (a conflict with feature) and the first line of other.txt; feature changes file.txt. */
    private fun divergedWithOther() {
        commit("Base")
        commit("Other", "other.txt", "1\n2\n3")
        git("switch", "-q", "-c", "feature")
        commit("A")
        git("switch", "-q", "main")
        repo.resolve("file.txt").writeText("Main\n")
        repo.resolve("other.txt").writeText("main\n2\n3\n")
        git("add", "-A")
        git("commit", "-q", "-m", "Main")
        git("switch", "-q", "feature")
    }

    @Test
    fun `local changes that git cannot apply when the merge is continued are kept in the stash`() {
        divergedWithOther()
        repo.resolve("other.txt").writeText("local\n2\n3\n")
        assertIs<GitMergeResult.Stopped>(merge(GitMerges(git)))
        repo.resolve("file.txt").writeText("resolved\n")

        val result = GitMerges(git).continueMerge(repo)

        val done = assertIs<GitMergeResult.Done>(result)
        assertTrue(done.stashKept, "the changes were kept as a stash entry")
        assertEquals(1, git("stash", "list").lines().size)
    }

    @Test
    fun `continuing a merge without conflicts stages nothing and checks nothing`() {
        diverged()
        git("merge", "--no-commit", "--no-ff", "main")
        val merges = recording()

        val result = merges.continueMerge(repo)

        assertIs<GitMergeResult.Done>(result)
        assertFalse(
            recordedCalls(callLog).any { "update-index" in it || "check-attr" in it },
            "${recordedCalls(callLog)}",
        )
    }

    @Test
    fun `a merge that git cannot continue or abort stays in progress, with git's message`() {
        diverged()
        git("merge", "--no-commit", "--no-ff", "main")
        // Git cannot write the index while this file is there.
        val lock = repo.resolve(".git/index.lock")
        lock.writeText("")

        val continued = GitMerges(git).continueMerge(repo)
        val continuedStopped = assertIs<GitMergeResult.Stopped>(continued)
        assertTrue("index.lock" in continuedStopped.message, continuedStopped.message)

        val aborted = GitMerges(git).abortMerge(repo)
        val abortedStopped = assertIs<GitMergeResult.Stopped>(aborted)
        assertTrue("index.lock" in abortedStopped.message, abortedStopped.message)
    }

    @Test
    fun `a squash after which another merge is in progress is stopped, not done`() {
        diverged()
        // The hook of git leaves a merge in progress after the squash.
        val hook = repo.resolve(".git/hooks/post-merge")
        hook.parent.createDirectories()
        hook.writeText("#!/bin/sh\ngit rev-parse main > .git/MERGE_HEAD\n")
        hook.toFile().setExecutable(true)

        val result = merge(GitMerges(git), GitMergeMode.SQUASH)

        assertIs<GitMergeResult.Stopped>(result)
    }
}
