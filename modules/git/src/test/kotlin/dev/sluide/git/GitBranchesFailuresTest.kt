package dev.sluide.git

import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Creating a branch and switching with a git that fails or a hook that does: refused names, what a failed switch
 * counts as, and what the result says after an undo. Real git; skipped without it.
 */
internal class GitBranchesFailuresTest : GitCommitsFixture() {
    private val branches = GitBranches(git)

    private val callLog get() = folder.resolve("calls.log")

    private fun failing(vararg words: String) = GitBranches(failingGit(folder, *words))

    private fun recording() = GitBranches(recordingGit(folder, callLog))

    private fun calls() = recordedCalls(callLog)

    @Test
    fun `creating a branch from a name git cannot take runs no git`() {
        val first = commit("First")
        val recorded = recording()

        val badName = recorded.createBranch(repo, "-x", first, checkout = false, overwrite = false)
        val badStart = recorded.createBranch(repo, "ok", "-y", checkout = false, overwrite = false)
        val emptyStart = recorded.createBranch(repo, "ok", "", checkout = true, overwrite = true)

        assertEquals(GitResult(false, "'-x' cannot be passed to git: it starts with '-'."), badName)
        assertEquals(GitResult(false, "'-y' cannot be passed to git: it starts with '-'."), badStart)
        assertEquals(GitResult(false, "An empty name cannot be passed to git."), emptyStart)
        assertEquals(emptyList(), calls())
    }

    @Test
    fun `a switch that fails without moving HEAD is not a change, on a branch or detached`() {
        val first = commit("First")
        val second = commit("Second")
        val failingSwitch = failing("switch")

        // On main at the target: git "failed", HEAD is where the target is, but on a branch, not detached.
        val onBranch = failingSwitch.switchDetached(repo, second)
        assertEquals(GitResult(false, "fatal: refused by test", changed = false), onBranch)

        // Detached elsewhere than the target.
        git("switch", "-q", "--detach", first)
        val elsewhere = failingSwitch.switchDetached(repo, second)
        assertEquals(GitResult(false, "fatal: refused by test", changed = false), elsewhere)

        val unknown = branches.switchDetached(repo, "refs/heads/none")
        assertFalse(unknown.success)
        assertFalse(unknown.changed)
    }

    @Test
    fun `a detached switch whose target cannot be read is no change, whatever HEAD is`() {
        val first = commit("First")
        commit("Second")
        git("switch", "-q", "--detach", first)

        // HEAD cannot be read either, so two unreadable commits must not look like the same commit.
        val result = failing("switch", "rev-parse").switchDetached(repo, "main")

        assertEquals(GitResult(false, "fatal: refused by test", changed = false), result)
    }

    @Test
    fun `a branch created with a failing git that changed nothing says only what git said`() {
        val first = commit("First")
        git("branch", "existing", first)
        val plain = branches.createBranch(repo, "existing", first, checkout = false, overwrite = false)

        // `branch -f` fails: an undo that moved the branch back would add its problem to the message.
        val exists = failing("-f").createBranch(repo, "existing", first, checkout = false, overwrite = false)
        val noStart = branches.createBranch(repo, "fresh", "refs/heads/none", checkout = false, overwrite = false)
        val noStartMoved =
            failing("-D").createBranch(repo, "existing", "refs/heads/none", checkout = false, overwrite = true)

        assertEquals(plain, exists)
        assertContains(plain.message, "already exists")
        assertEquals(1, noStart.message.lines().size, noStart.message)
        assertEquals(1, noStartMoved.message.lines().size, noStartMoved.message)
        assertFalse(noStart.success || noStartMoved.success)
        assertEquals(first, git("rev-parse", "existing"))
    }

    @Test
    fun `an undone creation says only what git said, for a new and for a moved branch`() {
        commit("First")
        val main = head()
        git("switch", "-q", "-c", "other")
        val other = commit("Other", content = "other")
        git("switch", "-q", "main")
        git("branch", "existing", main)
        repo.resolve("file.txt").writeText("local change\n")
        val refused = branches.switchBranch(repo, "other")

        val created = branches.createBranch(repo, "fresh", other, checkout = true, overwrite = false)
        val moved = branches.createBranch(repo, "existing", other, checkout = true, overwrite = true)

        assertEquals(GitResult(false, refused.message), created)
        assertEquals(GitResult(false, refused.message), moved)
        assertContains(refused.message, "would be overwritten")
        assertEquals(main, git("rev-parse", "existing"), "moved back")
        assertFalse(branches.branchExists(repo, "fresh"), "deleted again")
    }

    @Test
    fun `a checkout whose hook fails after it says only the hook's output, and is not undone`() {
        val first = commit("First")
        git("branch", "other")
        repo.resolve(".git/hooks/post-checkout").apply {
            writeText("#!/bin/sh\necho 'hook failed' >&2\nexit 3\n")
            toFile().setExecutable(true)
        }
        val switched = branches.switchBranch(repo, "other")
        git("-c", "core.hooksPath=/dev/null", "switch", "-q", "main")

        val created = branches.createBranch(repo, "hooked", first, checkout = true, overwrite = false)

        assertTrue(created.changed)
        assertEquals(GitResult(false, switched.message.replace("other", "hooked"), changed = true), created)
        assertEquals("hooked", git("branch", "--show-current"))
    }
}
