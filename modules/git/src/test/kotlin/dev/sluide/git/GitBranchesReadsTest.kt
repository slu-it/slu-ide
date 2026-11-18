package dev.sluide.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [GitBranches] reads and what they say for a name git cannot take or when git fails. Real git; skipped without it.
 */
internal class GitBranchesReadsTest : GitCommitsFixture() {
    private val branches = GitBranches(git)

    private val callLog get() = folder.resolve("calls.log")

    private fun failing(vararg words: String) = GitBranches(failingGit(folder, *words))

    private fun recording() = GitBranches(recordingGit(folder, callLog))

    private fun calls() = recordedCalls(callLog)

    @Test
    fun `a branch tip of a name git cannot take is unknown`() {
        commit("First")
        assertEquals(GitBranchTip.Unknown, branches.branchTip(repo, "-x"))
        assertEquals(GitBranchTip.Unknown, branches.branchTip(repo, ""))
    }

    @Test
    fun `ancestors of names git cannot take are null, without running git`() {
        val first = commit("First")
        val recorded = recording()

        assertNull(recorded.isAncestor(repo, "-x", first))
        assertNull(recorded.isAncestor(repo, first, "-x"))
        assertNull(recorded.isAncestor(repo, "", first))
        assertNull(recorded.isAncestor(repo, first, ""))

        assertEquals(emptyList(), calls())
        assertEquals(true, recorded.isAncestor(repo, first, first))
    }

    @Test
    fun `a leftBehind of a name git cannot take is null, without running git`() {
        commit("First")
        val recorded = recording()

        assertNull(recorded.leftBehind(repo, "-x"))
        assertNull(recorded.leftBehind(repo, ""))

        assertEquals(emptyList(), calls())
    }

    @Test
    fun `on a branch nothing is left behind and git does not count`() {
        commit("First")
        val recorded = recording()

        assertEquals(0, recorded.leftBehind(repo, "HEAD"))

        assertTrue(calls().none { "rev-list" in it }, calls().toString())
        // A git that fails every count shows it is not asked.
        assertEquals(0, failing("rev-list").leftBehind(repo, "HEAD"))
    }

    @Test
    fun `a detached HEAD whose count fails, or whose branch cannot be read, leaves null`() {
        val first = commit("First")
        git("switch", "-q", "--detach", first)

        assertNull(failing("rev-list").leftBehind(repo, "HEAD"))
        assertNull(failing("symbolic-ref").leftBehind(repo, "HEAD"))
        assertEquals(0, branches.leftBehind(repo, "HEAD"))
    }

    @Test
    fun `a branch exists only under its exact name`() {
        val first = commit("First")
        git("branch", "team/fix", first)

        assertTrue(branches.branchExists(repo, "main"))
        assertTrue(branches.branchExists(repo, "team/fix"))
        assertFalse(branches.branchExists(repo, "team"), "a folder of branches is no branch")
        assertFalse(branches.branchExists(repo, "none"))
        assertFalse(failing("for-each-ref").branchExists(repo, "main"), "false if git fails")
    }

    @Test
    fun `a failed deletion whose remaining branches cannot be read lists none`() {
        val first = commit("First")
        git("switch", "-q", "-c", "lonely")
        commit("Lonely", file = "lonely.txt")
        git("switch", "-q", "main")
        git("branch", "merged", first)

        val deletion = failing("for-each-ref").deleteBranches(repo, listOf("lonely"), force = false)

        assertFalse(deletion.result.success)
        assertEquals(emptyList(), deletion.notDeleted)
    }

    @Test
    fun `canPass tells whether the JVM can hand the argument to git`() {
        assertTrue(branches.canPass("feature/x"))
        assertFalse(branches.canPass("lone-surrogate-\uD800"))
    }
}
