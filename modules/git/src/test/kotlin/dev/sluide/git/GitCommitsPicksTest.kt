package dev.sluide.git

import dev.sluide.git.cli.GitCli
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Cherry-picks and reverts with a git that fails one command or records its calls: what [GitCommits.pick],
 * [GitCommits.continuePick] and [GitCommits.abortPick] say when reads fail, and what each stop or failure leaves.
 * Real git; skipped without it.
 */
internal class GitCommitsPicksTest : GitCommitsFixture() {
    private val callLog get() = folder.resolve("calls.log")

    private fun failing(
        vararg words: String,
        after: Int = 0,
    ) = GitCommits(failingGit(folder, *words, after = after))

    private fun failingNth(
        word: String,
        nth: Int,
    ) = GitCommits(failingNthGit(folder, word, nth))

    private fun recording() = GitCommits(recordingGit(folder, callLog))

    private fun calls() = recordedCalls(callLog)

    private fun appliedAlready(): String {
        commit("Base")
        git("switch", "-q", "-c", "other")
        val picked = commit("Change", "kept.txt", "kept")
        git("switch", "-q", "main")
        git("merge", "-q", "--ff-only", "other")
        return picked
    }

    @Test
    fun `a pick of a name git cannot take fails with the reason and runs no git`() {
        commit("Base")
        val recorded = recording()

        assertEquals(
            GitPickResult.Failed("'-x' cannot be passed to git: it starts with '-'."),
            recorded.pick(repo, GitPick.CHERRY_PICK, "-x", firstParent = false),
        )
        assertEquals(emptyList(), calls())
    }

    @Test
    fun `a cherry-pick and a revert pass their options and the commit after end-of-options`() {
        commit("Base")
        val added = commit("Add", "added.txt", "added")
        val recorded = recording()

        val reverted = recorded.pick(repo, GitPick.REVERT, added, firstParent = false)
        assertEquals(GitPickResult.Done(head()), reverted)
        assertEquals(
            listOf("revert --no-edit --end-of-options $added"),
            calls().filter { it.startsWith("revert") },
        )
        val revert = head()
        assertIs<GitPickResult.Done>(recorded.pick(repo, GitPick.REVERT, revert, firstParent = false))
        git("reset", "-q", "--hard", added)
        assertIs<GitPickResult.Done>(recorded.pick(repo, GitPick.CHERRY_PICK, revert, firstParent = false))
        assertEquals(
            listOf("cherry-pick --end-of-options $revert"),
            calls().filter { it.startsWith("cherry-pick") },
        )
    }

    @Test
    fun `picking, continuing and aborting say when what is in progress cannot be read`() {
        commit("Base")
        val failing = failing("--git-path")

        assertEquals(
            GitPickResult.Failed(IN_PROGRESS_UNREADABLE),
            failing.pick(repo, GitPick.CHERRY_PICK, head(), firstParent = false),
        )
        assertEquals(GitPickResult.Failed(IN_PROGRESS_UNREADABLE), failing.continuePick(repo, GitPick.REVERT))
        assertEquals(GitResult(false, IN_PROGRESS_UNREADABLE), failing.abortPick(repo, GitPick.REVERT))
    }

    @Test
    fun `continuing or aborting a pick of the other kind, or none, says which is not in progress`() {
        val theirs = conflicting()
        assertEquals(
            GitPickResult.Failed("No cherry-pick is in progress."),
            commits.continuePick(repo, GitPick.CHERRY_PICK),
        )
        assertEquals(GitResult(false, "No revert is in progress."), commits.abortPick(repo, GitPick.REVERT))

        commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)

        assertEquals(GitPickResult.Failed("No revert is in progress."), commits.continuePick(repo, GitPick.REVERT))
        assertEquals(GitResult(false, "No revert is in progress."), commits.abortPick(repo, GitPick.REVERT))
        assertIs<GitPendingPick>(commits.inProgress(repo), "left as it was")
        assertTrue(commits.abortPick(repo, GitPick.CHERRY_PICK).success)
    }

    @Test
    fun `continuing a pick stops where the conflicts cannot be read, checked or staged`() {
        val theirs = conflicting()
        commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)
        repo.resolve("file.txt").writeText("resolved\n")

        assertEquals(
            GitPickResult.Failed(CONFLICTS_UNREADABLE),
            failing("ls-files", after = 1).continuePick(repo, GitPick.CHERRY_PICK),
            "the unmerged entries",
        )
        assertEquals(
            GitPickResult.Failed(CONFLICTS_UNREADABLE),
            failing("check-attr").continuePick(repo, GitPick.CHERRY_PICK),
            "the attributes",
        )
        val staging = failing("update-index").continuePick(repo, GitPick.CHERRY_PICK)
        assertEquals(GitPickResult.Failed("fatal: refused by test"), staging)
        assertIs<GitPendingPick>(commits.inProgress(repo), "nothing was committed")
    }

    @Test
    fun `continuing asks the commits' own can-name-file, so a file the JVM cannot name goes through git`() {
        val theirs = conflicting()
        commits.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)
        repo.resolve("file.txt").writeText("resolved\n")
        val unnamed = failing("hash-object")
        unnamed.canNameFile = { false }

        assertEquals(GitPickResult.Failed(CONFLICTS_UNREADABLE), unnamed.continuePick(repo, GitPick.CHERRY_PICK))
        assertIs<GitPickResult.Done>(commits.continuePick(repo, GitPick.CHERRY_PICK))
    }

    @Test
    fun `a pick stopped by a failing signature is stopped with no conflicts`() {
        commit("Base")
        git("switch", "-q", "-c", "other")
        val added = commit("Add", "added.txt", "added")
        git("switch", "-q", "main")
        // A commit that cannot be signed stops the pick after it staged the changes.
        git("config", "commit.gpgSign", "true")
        git("config", "gpg.program", "false")

        val result = commits.pick(repo, GitPick.CHERRY_PICK, added, firstParent = false)

        assertIs<GitPickResult.Stopped>(result)
        assertEquals(emptyList(), result.conflicts)
        assertTrue(result.message.isNotBlank(), "git says why")
        assertEquals(listOf("A  added.txt"), status())
        assertIs<GitPendingPick>(commits.inProgress(repo))
    }

    @Test
    fun `a cherry-pick with nothing to commit that cannot be skipped is a failure`() {
        commit("Base")
        git("switch", "-q", "-c", "other")
        val picked = commit("Change", "kept.txt", "kept")
        git("switch", "-q", "main")
        git("merge", "-q", "--ff-only", "other")

        val result = failing("--skip").pick(repo, GitPick.CHERRY_PICK, picked, firstParent = false)

        assertEquals(GitPickResult.Failed("fatal: refused by test"), result)
    }

    @Test
    fun `a revert with nothing to commit that cannot be cleared is a failure, with what went wrong`() {
        commit("Base")
        val added = commit("Add", "added.txt", "added")
        assertIs<GitPickResult.Done>(commits.pick(repo, GitPick.REVERT, added, firstParent = false))

        assertEquals(
            GitPickResult.Failed("fatal: refused by test"),
            failing("update-ref").pick(repo, GitPick.REVERT, added, firstParent = false),
        )
        assertEquals(
            GitPickResult.Failed(IN_PROGRESS_UNREADABLE),
            // The reads before it (the pick's and the outcome's) work, the one for MERGE_MSG's path fails.
            failing("--git-path", after = 2).pick(repo, GitPick.REVERT, added, firstParent = false),
        )
    }

    @Test
    fun `a revert whose message file cannot be deleted says so`() {
        commit("Base")
        val added = commit("Add", "added.txt", "added")
        assertIs<GitPickResult.Done>(commits.pick(repo, GitPick.REVERT, added, firstParent = false))
        // After a revert, MERGE_MSG becomes a folder with a file in it, which cannot be deleted as a file.
        val wrapper = folder.resolve("git-folder-message")
        wrapper.writeText(
            FAKE_GIT_START +
                "git \"\$@\"; code=\$?\ncase \" \$* \" in *' revert '*) m=.git/MERGE_MSG; rm -rf \$m; " +
                "mkdir \$m; echo x > \$m/x;; esac\nexit \$code\n",
        )
        wrapper.toFile().setExecutable(true)
        val stuck = GitCommits(GitCli(wrapper.toString(), testGitEnvironment()))

        val result = stuck.pick(repo, GitPick.REVERT, added, firstParent = false)

        assertIs<GitPickResult.Failed>(result)
        assertTrue(" could not be deleted: " in result.message, result.message)
        val message = repo.resolve(".git/MERGE_MSG").toString()
        assertTrue(result.message.startsWith(message), result.message)
        assertTrue(result.message.endsWith(": $message"), "what the file system said: ${result.message}")
    }

    @Test
    fun `a pick stopped without conflicts is continued without looking at conflicts`() {
        commit("Base")
        git("switch", "-q", "-c", "other")
        val added = commit("Add", "added.txt", "added")
        git("switch", "-q", "main")
        git("config", "commit.gpgSign", "true")
        git("config", "gpg.program", "false")
        assertIs<GitPickResult.Stopped>(commits.pick(repo, GitPick.CHERRY_PICK, added, firstParent = false))
        git("config", "commit.gpgSign", "false")
        val noConflicts = failing("--deleted", "check-attr", "update-index", "hash-object")

        val result = noConflicts.continuePick(repo, GitPick.CHERRY_PICK)

        assertEquals(GitPickResult.Done(head()), result)
        assertEquals("Add", git("log", "-1", "--format=%s"))
    }

    @Test
    fun `a pick whose state cannot be read afterwards is no stop and no empty pick`() {
        val picked = appliedAlready()

        // The first read of what is in progress is the pick's own, the second the outcome's.
        // Only the outcome's read fails, so that clearing a revert would work: it must not be tried for a cherry-pick.
        val empty = failingNth("--git-path", 2).pick(repo, GitPick.CHERRY_PICK, picked, firstParent = false)
        assertIs<GitPickResult.Failed>(empty)
        git("cherry-pick", "--quit")
    }

    @Test
    fun `a conflict whose state cannot be read afterwards is a failure`() {
        val theirs = conflicting()

        val conflict = failing("--git-path", after = 1).pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)

        assertIs<GitPickResult.Failed>(conflict)
    }

    @Test
    fun `unreadable unmerged paths leave a pick stopped without conflicts and a revert not cleared`() {
        val picked = appliedAlready()
        // Reads of ls-files: the pick's, the outcome's, then the unmerged paths, which fail.
        val cherry = failing("ls-files", after = 2).pick(repo, GitPick.CHERRY_PICK, picked, firstParent = false)
        assertIs<GitPickResult.Stopped>(cherry)
        assertEquals(emptyList(), cherry.conflicts)
        git("cherry-pick", "--quit")

        val added = commit("Add", "added.txt", "added")
        assertIs<GitPickResult.Done>(commits.pick(repo, GitPick.REVERT, added, firstParent = false))
        val revert = failing("ls-files", after = 2).pick(repo, GitPick.REVERT, added, firstParent = false)
        assertIs<GitPickResult.Failed>(revert)
    }

    @Test
    fun `a conflict without a CHERRY_PICK_HEAD, as with GIT_CHERRY_PICK_HELP set, is a failure`() {
        val theirs = conflicting()
        // `git rebase -x` sets it; git then leaves the conflicts but no CHERRY_PICK_HEAD to tell what is in progress.
        val environment = testGitEnvironment() + ("GIT_CHERRY_PICK_HELP" to "continue it")
        val helped = GitCommits(GitCli("git", environment))

        val result = helped.pick(repo, GitPick.CHERRY_PICK, theirs, firstParent = false)

        assertIs<GitPickResult.Failed>(result)
        assertFalse(gitFile("CHERRY_PICK_HEAD").exists())
    }

    @Test
    fun `a pick while a merge is stopped is refused with what is in progress`() {
        conflicting()
        assertTrue(runCatching { git("merge", "other") }.isFailure, "conflicts")

        assertEquals(
            GitPickResult.Failed("A merge is in progress; finish or abort it first."),
            commits.pick(repo, GitPick.CHERRY_PICK, head(), firstParent = false),
        )
    }
}
