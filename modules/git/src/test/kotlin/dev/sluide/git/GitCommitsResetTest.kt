package dev.sluide.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What [GitCommits] reads before a reset, and the reset itself, with a git that fails one command or records its
 * calls. Real git; skipped without it.
 */
internal class GitCommitsResetTest : GitCommitsFixture() {
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

    @Test
    fun `staged paths that cannot be read give no count even if the unstaged ones can`() {
        commit("Base")

        assertNull(failing("--cached").stagedOnlyCount(repo))
    }

    @Test
    fun `a reset to a name git cannot take fails with the reason and runs no git`() {
        commit("Base")
        val recorded = recording()

        assertEquals(
            GitResult(false, "'-x' cannot be passed to git: it starts with '-'."),
            recorded.reset(repo, "-x", GitResetMode.HARD),
        )
        assertNull(recorded.unreachableCount(repo, "-x"))
        assertEquals(emptyList(), calls())
    }

    @Test
    fun `the commits a reset would leave are null for a target or branch git cannot take, or a failing git`() {
        val first = commit("First")
        commit("Second")

        assertNull(commits.unreachableCount(repo, "-x"))
        assertNull(failing("symbolic-ref").unreachableCount(repo, first))
        assertEquals(1, commits.unreachableCount(repo, first))
        // A branch whose name starts with a dash: it cannot be excluded by name.
        git("update-ref", "refs/heads/-odd", head())
        git("symbolic-ref", "HEAD", "refs/heads/-odd")
        assertNull(commits.unreachableCount(repo, first))
        git("switch", "-q", "--detach", "main")
        assertEquals(0, commits.unreachableCount(repo, head()))
    }

    @Test
    fun `counting files with staged changes that differ from the disk is null when git fails`() {
        commit("Base")

        assertEquals(0, commits.stagedOnlyCount(repo))
        assertNull(failing("diff").stagedOnlyCount(repo), "the staged paths")
        assertNull(failing("diff", after = 1).stagedOnlyCount(repo), "the unstaged paths")
    }

    @Test
    fun `a soft reset is refused during a merge without conflicts, and not otherwise`() {
        commit("Base")
        assertEquals(false, commits.softResetRefused(repo))
        assertNull(failing("ls-files").softResetRefused(repo))

        git("switch", "-q", "-c", "other")
        commit("Other", "other.txt")
        git("switch", "-q", "main")
        git("merge", "-q", "--no-commit", "--no-ff", "other")

        assertEquals(emptyList(), git("ls-files", "--unmerged").lines().filter(String::isNotEmpty))
        assertEquals(true, commits.softResetRefused(repo))
    }
}
