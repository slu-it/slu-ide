package dev.sluide.git

import dev.sluide.git.cli.GitCli
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [GitBranches]: names git is not run for, a failing git in the middle of an operation, and what its results say when
 * an undo went well. With the real git; skipped without it.
 */
internal class GitBranchesGapsTest : GitCommitsFixture() {
    private val branches = GitBranches(git)

    private val callLog get() = folder.resolve("calls.log")

    /** The real git behind a wrapper that writes each call's arguments as a line to [callLog]. */
    private fun recording(): GitBranches {
        val wrapper = folder.resolve("git-recorder")
        // The calls GitCli makes on its own (the version, the environment variables, a hook setting) are left out.
        wrapper.writeText(
            FAKE_GIT_START.replace("exit 1;; esac", "exec git \"\$@\";; esac") +
                "echo \"\$*\" >> '$callLog'\nexec git \"\$@\"\n",
        )
        wrapper.toFile().setExecutable(true)
        return GitBranches(GitCli(wrapper.toString(), testGitEnvironment()))
    }

    private fun calls() = if (callLog.exists()) callLog.readText().lines().filter(String::isNotEmpty) else emptyList()

    private fun failing(vararg words: String) = GitBranches(failingGit(folder, *words))

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
    fun `a write with a name git cannot take is refused with the reason, and runs no git`() {
        val first = commit("First")
        val recorded = recording()

        val results =
            listOf(
                recorded.createTag(repo, "-t", first, message = null),
                recorded.createTag(repo, "t", "-c", message = "message"),
                recorded.renameBranch(repo, "-a", "b"),
                recorded.renameBranch(repo, "main", "-b"),
                recorded.deleteBranches(repo, listOf("main", "-d"), force = false).result,
                recorded.deleteTags(repo, listOf("-d")),
                recorded.switchBranch(repo, "-s"),
                recorded.switchDetached(repo, "-s"),
            )

        assertEquals(
            listOf("-t", "-c", "-a", "-b", "-d", "-d", "-s", "-s").map {
                GitResult(false, "'$it' cannot be passed to git: it starts with '-'.")
            },
            results,
        )
        assertTrue(calls().none { "tag" in it || "branch" in it || "switch" in it }, calls().toString())
        assertEquals(listOf("main"), git("for-each-ref", "--format=%(refname:short)", "refs/heads").lines())
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
    fun `canPass tells whether the JVM can hand the argument to git`() {
        assertTrue(branches.canPass("feature/x"))
        assertFalse(branches.canPass("lone-surrogate-\uD800"))
    }

    @Test
    fun `a switch that fails with HEAD detached at the target is a change`() {
        val first = commit("First")
        val second = commit("Second")
        git("switch", "-q", "--detach", first)
        // The first switch is let through, but fails after moving HEAD: a hook that fails after the checkout.
        repo.resolve(".git/hooks/post-checkout").apply {
            writeText("#!/bin/sh\nexit 3\n")
            toFile().setExecutable(true)
        }

        val result = branches.switchDetached(repo, second)

        assertFalse(result.success)
        assertTrue(result.changed)
        assertEquals(second, head())
    }

    @Test
    fun `a branch created with a failing git that changed nothing says only what git said`() {
        val first = commit("First")
        git("branch", "existing", first)

        // `branch -f` fails: an undo that moved the branch back would say so.
        val exists = failing("-f").createBranch(repo, "existing", first, checkout = false, overwrite = false)
        val noStart = branches.createBranch(repo, "fresh", "refs/heads/none", checkout = false, overwrite = false)
        val noStartMoved =
            branches.createBranch(
                repo,
                "existing",
                "refs/heads/none",
                checkout = false,
                overwrite = true,
            )

        assertFalse(exists.success)
        assertContains(exists.message, "already exists")
        assertFalse("could not" in exists.message, exists.message)
        assertFalse(noStart.success)
        assertFalse("could not" in noStart.message, noStart.message)
        assertFalse(noStartMoved.success)
        assertFalse("could not" in noStartMoved.message, noStartMoved.message)
        assertEquals(first, git("rev-parse", "existing"))
    }

    @Test
    fun `a checkout that works or whose hook fails after it adds no undo problem to the result`() {
        val first = commit("First")

        val worked = branches.createBranch(repo, "worked", first, checkout = true, overwrite = false)

        assertTrue(worked.success)
        assertFalse("could not" in worked.message, worked.message)
        assertEquals("worked", git("branch", "--show-current"))

        repo.resolve(".git/hooks/post-checkout").apply {
            writeText("#!/bin/sh\necho 'hook failed' >&2\nexit 3\n")
            toFile().setExecutable(true)
        }
        val hooked = branches.createBranch(repo, "hooked", first, checkout = true, overwrite = false)

        assertTrue(hooked.changed)
        assertContains(hooked.message, "hook failed")
        assertFalse("could not" in hooked.message, hooked.message)
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

        val created = branches.createBranch(repo, "fresh", other, checkout = true, overwrite = false)
        val moved = branches.createBranch(repo, "existing", other, checkout = true, overwrite = true)

        assertFalse(created.success)
        assertContains(created.message, "would be overwritten")
        assertFalse("could not" in created.message, created.message)
        assertFalse("null" in created.message, created.message)
        assertFalse(moved.success)
        assertFalse("could not" in moved.message, moved.message)
        assertFalse("null" in moved.message, moved.message)
        assertEquals(main, git("rev-parse", "existing"), "moved back")
        assertFalse(branches.branchExists(repo, "fresh"), "deleted again")
    }
}
