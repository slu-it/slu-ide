package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.TestRepository
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

    private lateinit var repository: TestRepository

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repository = TestRepository(repo)
    }

    /** main: Base, Main (changes file.txt if [conflict], else adds main.txt); feature: Base, A. feature checked out. */
    private fun diverged(conflict: Boolean = false) {
        repository.commit("Base")
        repository.git("switch", "-q", "-c", "feature")
        repository.commit("A")
        repository.git("switch", "-q", "main")
        if (conflict) repository.commit("Main") else repository.commit("Main", "main.txt")
        repository.git("switch", "-q", "feature")
    }

    private fun merge(
        merges: GitMerges,
        mode: GitMergeMode = GitMergeMode.MERGE_COMMIT,
        message: String? = null,
        autostash: Boolean = true,
        ref: String = "refs/heads/main",
    ) = merges.merge(repo, "feature", ref, mode, message, autostash)

    private fun message() = repository.git("log", "-1", "--format=%B")

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
        assertEquals("A", repository.git("log", "-1", "--format=%s"))
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
        assertEquals("A", repository.git("log", "-1", "--format=%s"))
    }

    @Test
    fun `a merge that stopped is a failure with git's message when what is in progress cannot be read afterwards`() {
        diverged(conflict = true)

        // The reads after the merge command fail.
        val result = merge(GitMerges(failingAfterGit(folder, "merge", "--git-path")))

        val failed = assertIs<GitMergeResult.Failed>(result)
        assertTrue("CONFLICT" in failed.message, failed.message)
    }

    @Test
    fun `aborting or continuing a merge is told done when what is in progress cannot be read afterwards`() {
        diverged(conflict = true)
        assertIs<GitMergeResult.Stopped>(merge(GitMerges(git)))
        val before = repository.git("rev-parse", "HEAD")

        assertEquals(
            GitMergeResult.Done(before),
            GitMerges(failingAfterGit(folder, "merge", "--git-path")).abortMerge(repo),
        )

        assertIs<GitMergeResult.Stopped>(merge(GitMerges(git)))
        repo.resolve("file.txt").writeText("resolved\n")
        val continued = GitMerges(failingAfterGit(folder, "merge", "--git-path")).continueMerge(repo)
        assertIs<GitMergeResult.Done>(continued)
        assertEquals("resolved\n", repo.resolve("file.txt").toFile().readText())
    }

    @Test
    fun `continuing fails with the reason when the conflicts cannot be read, checked or staged`() {
        diverged(conflict = true)
        assertIs<GitMergeResult.Stopped>(merge(GitMerges(git)))
        repo.resolve("file.txt").writeText("resolved\n")
        val unreadable = GitMergeResult.Failed(CONFLICTS_UNREADABLE)

        // `--unmerged` is read first to find what is in progress, then for the unmerged entries: git cannot tell the two
        // by their arguments, so the count of 1 lets the first through and fails the entries.
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

        repository.git("reset", "-q", "--hard", "HEAD^")
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
        repository.git("update-ref", "ORIG_HEAD", "main")
        val merges = recording()

        assertIs<GitMergeResult.Done>(merge(merges, GitMergeMode.MERGE_COMMIT, ref = "ORIG_HEAD"))

        assertTrue(mergeCall().endsWith("--end-of-options ORIG_HEAD"), mergeCall())
        // Not "Merge commit 'ORIG_HEAD'" as git would say it: the default message of a branch.
        assertEquals("Merge branch 'ORIG_HEAD' into feature", message())
    }

    @Test
    fun `a squash with local changes in the way fails with git's message`() {
        diverged()
        repository.commit("More", "main.txt")
        repository.git("switch", "-q", "main")
        repository.commit("Main again", "main.txt")
        repository.git("switch", "-q", "feature")
        repo.resolve("main.txt").writeText("local\n")

        // Git refuses ("Merge with strategy ort failed.", exit 2) and leaves no conflicts: no squash was done.
        val refused = merge(GitMerges(git), GitMergeMode.SQUASH)

        val failed = assertIs<GitMergeResult.Failed>(refused)
        assertTrue("would be overwritten" in failed.message, failed.message)
    }

    @Test
    fun `a squash whose conflicts cannot be listed is no squash`() {
        diverged(conflict = true)
        val head = repository.git("rev-parse", "HEAD")

        // Git exits with 1 and leaves conflicts; the reads of the unmerged paths after the merge command fail.
        val result = merge(GitMerges(failingAfterGit(folder, "merge", "--unmerged")), GitMergeMode.SQUASH)

        val failed = assertIs<GitMergeResult.Failed>(result)
        assertTrue(failed.message.startsWith("Auto-merging file.txt"), failed.message)
        assertTrue("CONFLICT (content): Merge conflict in file.txt" in failed.message, failed.message)
        assertEquals(head, repository.git("rev-parse", "HEAD"))
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

        assertEquals(
            GitMergeResult.Done(repository.git("rev-parse", "HEAD"), squashed = true, stashKept = true),
            result,
        )
        assertEquals(1, repository.git("stash", "list").lines().size)
    }

    @Test
    fun `continuing a merge without conflicts stages nothing and checks nothing`() {
        diverged()
        repository.git("merge", "--no-commit", "--no-ff", "main")
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
        repository.git("merge", "--no-commit", "--no-ff", "main")
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

    @Test
    fun `a message that cannot be written fails the merge with the reason, whether the file or its text is at fault`() {
        diverged()
        val head = repository.git("rev-parse", "HEAD")
        val merges = GitMerges(git)
        merges.temporaryFolder = folder.resolve("no-such-folder")

        val noFile = assertIs<GitMergeResult.Failed>(merge(merges, message = "By hand"))
        assertTrue(noFile.message.startsWith("The message could not be written: "), noFile.message)
        assertTrue("no-such-folder" in noFile.message, noFile.message)

        // A lone surrogate has no UTF-8 encoding: the file is made, the text cannot be written to it.
        val inFolder = folder.resolve("messages").createDirectories()
        merges.temporaryFolder = inFolder
        val noText = merge(merges, message = "Broken \uD800")
        assertEquals(GitMergeResult.Failed("The message could not be written: Input length = 1"), noText)
        assertEquals(emptyList(), inFolder.toFile().list()?.toList(), "the file is deleted")
        assertEquals(head, repository.git("rev-parse", "HEAD"))
    }

    // The wrapper makes itself not executable when it is asked for the stash, the last read before the merge command:
    // the next start of git fails, as when git is gone (GitCli.available is read once, when it is made).
    @Test
    fun `a squash with a git that cannot be started fails with the reason`() {
        diverged()
        val script = folder.resolve("vanishing-git")
        script.writeText(
            FAKE_GIT_START + "case \" \$* \" in *' refs/stash '*) chmod -x \"\$0\";; esac\nexec git \"\$@\"\n",
        )
        script.toFile().setExecutable(true)

        val result = merge(GitMerges(GitCli(script.toString(), testGitEnvironment())), GitMergeMode.SQUASH)

        assertEquals(GitMergeResult.Failed("git could not be run."), result)
    }
}
