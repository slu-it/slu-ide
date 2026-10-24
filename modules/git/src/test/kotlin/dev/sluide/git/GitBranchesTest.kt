package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.branchTip
import dev.sluide.git.cli.currentBranch
import dev.sluide.git.cli.isAncestor
import dev.sluide.git.cli.localBranches
import dev.sluide.git.cli.status
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the real git in temporary repositories; skipped if git is not installed. */
class GitBranchesTest {
    @TempDir
    lateinit var folder: Path

    private val repo get() = folder.resolve("repo")

    private val git = GitCli("git", testGitEnvironment())
    private val branches = GitBranches(git)

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

    /** Commits [file] with [content]; returns the commit's hash. */
    private fun commit(
        subject: String,
        file: String = "file.txt",
        content: String = subject,
    ): String {
        repo.resolve(file).writeText("$content\n")
        git("add", file)
        git("commit", "-q", "-m", subject)
        return git("rev-parse", "HEAD")
    }

    private fun head() = git("rev-parse", "HEAD")

    private fun currentBranch() = git("branch", "--show-current")

    private fun localBranches() = git("for-each-ref", "--format=%(refname:short)", "refs/heads").lines()

    private fun tags() = git("tag").lines().filter(String::isNotEmpty)

    @Test
    fun `switches to a local branch and refuses when local changes would be overwritten`() {
        commit("First")
        git("branch", "other")
        val second = commit("Second")

        val switched = branches.switchBranch(repo, "other")

        assertTrue(switched.success, switched.message)
        assertEquals("other", currentBranch())
        assertContains(switched.message, "Switched to branch 'other'")

        repo.resolve("file.txt").writeText("changed\n")
        val refused = branches.switchBranch(repo, "main")

        assertFalse(refused.success)
        assertContains(refused.message, "Your local changes to the following files would be overwritten by checkout")
        assertContains(refused.message, "file.txt")
        assertEquals("other", currentBranch())
        assertTrue(head() != second)
    }

    @Test
    fun `a checkout refuses to overwrite an ignored file the other commit tracks`() {
        commit("First")
        git("switch", "-q", "-c", "old")
        val old = commit("Tracked env", ".env", "tracked secret")
        git("switch", "-q", "main")
        commit("Ignore env", ".gitignore", ".env")
        repo.resolve(".env").writeText("LOCAL SECRET\n")

        val branch = branches.switchBranch(repo, "old")
        val detached = branches.switchDetached(repo, old)

        for (refused in listOf(branch, detached)) {
            assertFalse(refused.success)
            assertContains(refused.message, "would be overwritten by checkout")
            assertContains(refused.message, ".env")
        }
        assertEquals("main", currentBranch())
        assertEquals("LOCAL SECRET\n", repo.resolve(".env").readText())
    }

    @Test
    fun `checks out a commit or tag detached`() {
        val first = commit("First")
        git("tag", "v1", first)
        commit("Second")

        val detached = branches.switchDetached(repo, first)

        assertTrue(detached.success, detached.message)
        assertEquals(first, head())
        assertEquals("", currentBranch())

        git("switch", "-q", "main")
        val tag = branches.switchDetached(repo, "refs/tags/v1")

        assertTrue(tag.success, tag.message)
        assertEquals(first, head())
        assertEquals("", currentBranch())
    }

    @Test
    fun `counts the commits of a detached HEAD a checkout would leave on no branch or tag`() {
        val first = commit("First")
        git("branch", "other")
        val second = commit("Second")
        assertEquals(0, branches.leftBehind(repo, "refs/heads/other"), "a branch keeps its commits")

        git("switch", "-q", "--detach", first)
        assertEquals(0, branches.leftBehind(repo, "refs/heads/main"), "a detached HEAD on a branch's commit")

        commit("Detached one")
        val detached = commit("Detached two")
        assertEquals(2, branches.leftBehind(repo, "refs/heads/other"))
        assertEquals(2, branches.leftBehind(repo, second))
        assertEquals(0, branches.leftBehind(repo, detached), "the target keeps them")
        assertEquals(0, branches.leftBehind(repo, "HEAD"))

        git("tag", "keep", "HEAD~1")
        assertEquals(1, branches.leftBehind(repo, "refs/heads/main"), "a tag keeps the commits below it")
        assertNull(branches.leftBehind(repo, "-x"))

        // As for git's warning, any ref keeps them, the stash too, whose commit has HEAD as parent.
        repo.resolve("file.txt").writeText("stashed\n")
        git("stash", "-q")
        assertEquals(0, branches.leftBehind(repo, "refs/heads/main"))
    }

    @Test
    fun `checks out a remote branch as a new local branch tracking it`() {
        commit("First")
        val remote = folder.resolve("remote.git")
        git("init", "-q", "--bare", "-b", "main", remote.toString(), directory = folder)
        git("remote", "add", "origin", remote.toString())
        git("switch", "-q", "-c", "fix")
        val fix = commit("Fix")
        git("push", "-q", "origin", "main", "fix")
        git("switch", "-q", "main")
        git("branch", "-q", "-D", "fix")
        git("fetch", "-q", "origin")

        assertFalse(branches.branchExists(repo, "fix"))
        assertTrue(branches.branchExists(repo, "main"))

        val result = branches.switchTracking(repo, "refs/remotes/origin/fix")

        assertTrue(result.success, result.message)
        assertEquals("fix", currentBranch())
        assertEquals(fix, head())
        assertEquals("origin/fix", git("rev-parse", "--abbrev-ref", "fix@{upstream}"))
        assertTrue(branches.branchExists(repo, "fix"))
    }

    @Test
    fun `creates a branch with or without checking it out, and overwrites only when asked`() {
        val first = commit("First")
        val second = commit("Second")

        val created = branches.createBranch(repo, "feature", first, checkout = false, overwrite = false)

        assertTrue(created.success, created.message)
        assertEquals("main", currentBranch())
        assertEquals(first, git("rev-parse", "feature"))

        val exists = branches.createBranch(repo, "feature", second, checkout = false, overwrite = false)

        assertFalse(exists.success)
        assertEquals("fatal: a branch named 'feature' already exists", exists.message)
        assertEquals(first, git("rev-parse", "feature"))

        val overwritten = branches.createBranch(repo, "feature", second, checkout = false, overwrite = true)

        assertTrue(overwritten.success, overwritten.message)
        assertEquals(second, git("rev-parse", "feature"))

        val checkedOut = branches.createBranch(repo, "topic", "refs/heads/feature", checkout = true, overwrite = false)

        assertTrue(checkedOut.success, checkedOut.message)
        assertEquals("topic", currentBranch())
        assertEquals(second, head())

        val existsToo = branches.createBranch(repo, "feature", first, checkout = true, overwrite = false)
        assertFalse(existsToo.success)
        assertEquals("topic", currentBranch())

        val reset = branches.createBranch(repo, "feature", first, checkout = true, overwrite = true)
        assertTrue(reset.success, reset.message)
        assertEquals("feature", currentBranch())
        assertEquals(first, head())
    }

    @Test
    fun `refuses to overwrite the current branch without checking out`() {
        val first = commit("First")
        commit("Second")

        val result = branches.createBranch(repo, "main", first, checkout = false, overwrite = true)

        assertFalse(result.success)
        assertContains(result.message, "cannot force update the branch 'main'")

        val checkedOut = branches.createBranch(repo, "main", first, checkout = true, overwrite = true)

        assertFalse(checkedOut.success, "nor when checking it out")
        assertTrue(head() != first)
    }

    /** `git status --porcelain`: empty for a clean working tree and index. */
    private fun status() = git("status", "--porcelain")

    /** A commit with another file, only reachable through `refs/remotes/origin/only-remote`: no remote is configured. */
    private fun remoteOnlyCommit(): String {
        git("switch", "-q", "-c", "tmp")
        val commit = commit("Remote work", file = "remote.txt")
        git("update-ref", "refs/remotes/origin/only-remote", commit)
        git("switch", "-q", "main")
        git("branch", "-q", "-D", "tmp")
        return commit
    }

    @Test
    fun `a remote branch whose tracking cannot be set up leaves the working tree, index and HEAD as they were`() {
        commit("First")
        val main = head()
        remoteOnlyCommit()

        val result = branches.switchTracking(repo, "refs/remotes/origin/only-remote")

        assertFalse(result.success)
        assertContains(result.message, "cannot set up tracking information")
        assertEquals("", status(), "nothing staged, nothing changed")
        assertEquals("main", currentBranch())
        assertEquals(main, head())
        assertEquals(listOf("main"), localBranches())
    }

    @Test
    fun `a new tracking branch that cannot be checked out is deleted again`() {
        commit("First")
        val remote = folder.resolve("remote.git")
        git("init", "-q", "--bare", "-b", "main", remote.toString(), directory = folder)
        git("remote", "add", "origin", remote.toString())
        git("switch", "-q", "-c", "fix")
        commit("Fix", content = "fixed")
        git("push", "-q", "origin", "main", "fix")
        git("switch", "-q", "main")
        git("branch", "-q", "-D", "fix")
        git("fetch", "-q", "origin")
        repo.resolve("file.txt").writeText("local change\n")

        val result = branches.switchTracking(repo, "refs/remotes/origin/fix")

        assertFalse(result.success)
        assertContains(result.message, "would be overwritten by checkout")
        assertEquals(listOf("main"), localBranches())
        assertEquals("main", currentBranch())
        assertEquals(" M file.txt", git("status", "--porcelain").let { " $it" })
    }

    @Test
    fun `a new branch is checked out in a second step, so a failure leaves nothing half switched`() {
        commit("First")
        val main = head()
        val remote = remoteOnlyCommit()

        // From a remote-tracking ref without a remote: no tracking, but created and checked out.
        val fromRemote =
            branches.createBranch(
                repo,
                "x",
                "refs/remotes/origin/only-remote",
                checkout = true,
                overwrite = false,
            )
        assertTrue(fromRemote.success, fromRemote.message)
        assertEquals("x", currentBranch())
        assertEquals(remote, head())
        assertEquals("", status())
        git("switch", "-q", "main")

        // A name clashing with a ref is refused before anything changes; git switch -c would have staged the files.
        git("branch", "feat")
        val clash = branches.createBranch(repo, "feat/y", remote, checkout = true, overwrite = false)
        assertFalse(clash.success)
        assertContains(clash.message, "cannot lock ref")
        assertEquals("", status())
        assertEquals("main", currentBranch())
        assertEquals(main, head())

        // A checkout git refuses deletes the new branch again, or moves an overwritten one back.
        repo.resolve("file.txt").writeText("changed\n")
        git("commit", "-q", "-am", "Change")
        repo.resolve("file.txt").writeText("local change\n")
        val refused = branches.createBranch(repo, "z", remote, checkout = true, overwrite = false)
        assertFalse(refused.success)
        assertContains(refused.message, "would be overwritten")
        assertEquals(listOf("feat", "main", "x"), localBranches())
        val moved = branches.createBranch(repo, "x", main, checkout = true, overwrite = true)
        assertFalse(moved.success)
        assertContains(moved.message, "would be overwritten")
        assertEquals(remote, git("rev-parse", "x"), "moved back")
        assertEquals("main", currentBranch())
    }

    /**
     * The real git behind a wrapper that fails, with "error: refused by test", every call whose arguments contain
     * [failing].
     */
    private fun wrapped(failing: String): GitBranches {
        val wrapper =
            folder.resolve("git-wrapper-${failing.hashCode()}").apply {
                writeText(
                    "#!/bin/sh\ncase \" $* \" in *'$failing'*) echo 'error: refused by test' >&2; exit 1;; esac\n" +
                        "exec git \"$@\"\n",
                )
                toFile().setExecutable(true)
            }
        return GitBranches(GitCli(wrapper.toString(), testGitEnvironment()))
    }

    @Test
    fun `nothing is changed when where a branch points cannot be read`() {
        val first = commit("First")
        git("branch", "feature")
        val second = commit("Second")
        val failing = wrapped(failing = " for-each-ref ")

        val result = failing.createBranch(repo, "feature", second, checkout = true, overwrite = true)

        assertEquals(GitResult(false, "The commit of branch 'feature' could not be read."), result)
        assertEquals(first, git("rev-parse", "feature"))
        assertEquals("main", currentBranch())
        assertFalse(failing.switchTracking(repo, "refs/remotes/origin/feature").success)
        assertEquals(listOf("feature", "main"), localBranches())
    }

    @Test
    fun `a failed undo is told after git's message, and an existing branch is moved back, never deleted`() {
        commit("First")
        val main = head()
        git("switch", "-q", "-c", "other")
        val other = commit("Other", content = "other")
        git("switch", "-q", "main")
        git("branch", "existing", main)
        repo.resolve("file.txt").writeText("local change\n")

        val notDeleted =
            wrapped(
                failing = "branch -D",
            ).createBranch(repo, "z", other, checkout = true, overwrite = false)

        assertFalse(notDeleted.success)
        assertContains(notDeleted.message, "would be overwritten")
        assertContains(notDeleted.message, "The new branch z could not be deleted again: error: refused by test")

        val notMoved =
            wrapped(failing = "existing $main").createBranch(repo, "existing", other, checkout = true, overwrite = true)

        assertFalse(notMoved.success)
        assertContains(
            notMoved.message,
            "Branch existing could not be moved back to ${main.take(8)}: error: refused by test",
        )
        assertEquals(other, git("rev-parse", "existing"), "still moved, as the message says")

        git("branch", "-f", "existing", main)
        val movedBack = branches.createBranch(repo, "existing", other, checkout = true, overwrite = true)
        assertFalse(movedBack.success)
        assertEquals(main, git("rev-parse", "existing"), "moved back, not deleted")
    }

    /** The real git behind a wrapper that writes each call's arguments as a line to [log]. */
    private fun recording(log: Path): GitBranches {
        val wrapper =
            folder.resolve("git-recorder").apply {
                writeText("#!/bin/sh\necho \"$*\" >> '$log'\nexec git \"$@\"\n")
                toFile().setExecutable(true)
            }
        return GitBranches(GitCli(wrapper.toString(), testGitEnvironment()))
    }

    @Test
    fun `overwriting a branch that is not there passes no -f`() {
        val first = commit("First")
        val log = folder.resolve("calls.log")
        val recorded = recording(log)

        assertTrue(recorded.createBranch(repo, "new", first, checkout = false, overwrite = true).success)
        git("branch", "existing", first)
        val second = commit("Second")
        assertTrue(recorded.createBranch(repo, "existing", second, checkout = false, overwrite = true).success)

        val branchCalls = log.readText().lines().filter { it.startsWith("branch") }
        assertEquals(
            listOf("branch --end-of-options new $first", "branch -f --end-of-options existing $second"),
            branchCalls,
            "a case-insensitive file system would write -f through to a branch named differently in case",
        )
    }

    @Test
    fun `whether a branch was created is told when it cannot be read after git failed`() {
        val first = commit("First")
        git("branch", "existing", first)
        val counter = folder.resolve("count")
        // The first for-each-ref (before) works, the second (after git failed) fails.
        val wrapper =
            folder.resolve("git-second-read-fails").apply {
                writeText(
                    "#!/bin/sh\ncase \" $* \" in *' for-each-ref '*)\n" +
                        "n=\$(cat '$counter' 2>/dev/null || echo 0); n=\$((n + 1)); echo \$n > '$counter'\n" +
                        "[ \$n -ge 2 ] && { echo 'error: refused by test' >&2; exit 1; };;\nesac\nexec git \"\$@\"\n",
                )
                toFile().setExecutable(true)
            }
        val failing = GitBranches(GitCli(wrapper.toString(), testGitEnvironment()))

        val result = failing.createBranch(repo, "existing", first, checkout = false, overwrite = false)

        assertFalse(result.success)
        assertContains(result.message, "a branch named 'existing' already exists")
        assertContains(result.message, "Whether branch existing was created could not be read.")
    }

    @Test
    fun `a checkout whose post-checkout hook fails is not undone`() {
        val first = commit("First")
        git("branch", "other")
        repo.resolve(".git/hooks/post-checkout").apply {
            writeText("#!/bin/sh\necho 'hook failed' >&2\nexit 3\n")
            toFile().setExecutable(true)
        }

        val created = branches.createBranch(repo, "hooked", first, checkout = true, overwrite = false)

        assertFalse(created.success)
        assertTrue(created.changed, "checked out all the same")
        assertContains(created.message, "hook failed")
        assertEquals("hooked", currentBranch())
        assertEquals(listOf("hooked", "main", "other"), localBranches())

        val switched = branches.switchBranch(repo, "other")
        assertEquals(GitResult(false, switched.message, changed = true), switched)
        assertEquals("other", currentBranch())

        git("tag", "-a", "-m", "Release", "v1", first)
        val detached = branches.switchDetached(repo, first)
        assertEquals(GitResult(false, detached.message, changed = true), detached, "a commit, detached")
        assertEquals("", currentBranch())
        git("-c", "core.hooksPath=/dev/null", "switch", "-q", "other")
        val tag = branches.switchDetached(repo, "refs/tags/v1")
        assertTrue(tag.changed, "an annotated tag's commit, detached")
        assertEquals(first, head())
        git("-c", "core.hooksPath=/dev/null", "switch", "-q", "other")

        repo.resolve("file.txt").writeText("local change\n")
        git("-c", "core.hooksPath=/dev/null", "switch", "-q", "main")
        git("commit", "-q", "-am", "Change")
        repo.resolve("file.txt").writeText("another change\n")
        val refused = branches.switchBranch(repo, "other")
        assertFalse(refused.changed, "a refused checkout changed nothing")
    }

    @Test
    fun `a branch git writes before failing to set up its tracking is deleted again`() {
        commit("First")
        git("update-ref", "refs/remotes/origin/x", head())
        // Two remotes map into refs/remotes/origin/: the tracking information is ambiguous.
        git("config", "remote.origin.url", "/nowhere")
        git("config", "remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/*")
        git("config", "remote.other.url", "/nowhere-else")
        git("config", "remote.other.fetch", "+refs/heads/*:refs/remotes/origin/*")

        val tracking = branches.switchTracking(repo, "refs/remotes/origin/x")
        val autoSetup = branches.createBranch(repo, "z", "refs/remotes/origin/x", checkout = false, overwrite = false)

        assertFalse(tracking.success)
        assertContains(tracking.message, "ambiguous information")
        assertFalse(autoSetup.success)
        assertEquals(listOf("main"), localBranches())
        assertEquals("", status())
    }

    @Test
    fun `tells branch tips and ancestors`() {
        val first = commit("First")
        val second = commit("Second")

        git("branch", "team/fix", first)

        assertEquals(GitBranchTip.At(second), branches.branchTip(repo, "main"))
        assertEquals(GitBranchTip.At(first), branches.branchTip(repo, "team/fix"))
        assertEquals(GitBranchTip.Missing, branches.branchTip(repo, "none"))
        assertEquals(GitBranchTip.Missing, branches.branchTip(repo, "team"), "a folder of branches is no branch")
        assertEquals(GitBranchTip.Unknown, wrapped(failing = " for-each-ref ").branchTip(repo, "main"))
        assertEquals(true, branches.isAncestor(repo, first, "refs/heads/main"))
        assertEquals(true, branches.isAncestor(repo, second, second))
        assertEquals(false, branches.isAncestor(repo, second, first))
        assertEquals(null, branches.isAncestor(repo, "refs/heads/none", first))
    }

    @Test
    fun `a write never waits for an editor`() {
        val first = commit("First")
        // A signed tag needs a message; git would open an editor for it.
        git("config", "tag.gpgSign", "true")
        val started = System.nanoTime()

        val result = branches.createTag(repo, "v1", first, message = null)

        assertFalse(result.success)
        assertTrue("editor" in result.message, result.message)
        assertTrue(System.nanoTime() - started < 10_000_000_000, "fails at once")
        assertEquals(emptyList(), tags())
    }

    @Test
    fun `creates lightweight and annotated tags, the message with non-ASCII characters on the standard input`() {
        val first = commit("First")

        val light = branches.createTag(repo, "v1", first, message = "  ")

        assertTrue(light.success, light.message)
        assertEquals("commit", git("cat-file", "-t", "v1"))

        val message = "Gr\u00fc\u00dfe aus K\u00f6ln \u2013 \u2603\n\n#1 stays\n"
        val annotated = branches.createTag(repo, "v2", first, message)

        assertTrue(annotated.success, annotated.message)
        assertEquals("tag", git("cat-file", "-t", "v2"))
        val body = git("cat-file", "tag", "v2").substringAfter("\n\n")
        assertEquals("Gr\u00fc\u00dfe aus K\u00f6ln \u2013 \u2603\n\n#1 stays", body)
        assertEquals(first, git("rev-parse", "v2^{commit}"))

        val exists = branches.createTag(repo, "v1", first, message = null)

        assertFalse(exists.success)
        assertEquals("fatal: tag 'v1' already exists", exists.message)
    }

    @Test
    fun `renames a branch, also the current one, and refuses an existing name`() {
        commit("First")
        git("branch", "a")
        git("branch", "b")

        val renamed = branches.renameBranch(repo, "a", "c")

        assertTrue(renamed.success, renamed.message)
        assertEquals(listOf("b", "c", "main"), localBranches())

        val exists = branches.renameBranch(repo, "c", "b")

        assertFalse(exists.success)
        assertContains(exists.message, "a branch named 'b' already exists")

        val current = branches.renameBranch(repo, "main", "trunk")

        assertTrue(current.success, current.message)
        assertEquals("trunk", currentBranch())
    }

    @Test
    fun `deletes merged branches, refuses unmerged ones until forced, and deletes tags`() {
        commit("First")
        git("branch", "merged")
        git("switch", "-q", "-c", "lonely")
        commit("Lonely", file = "lonely.txt")
        git("switch", "-q", "main")
        git("tag", "t1")
        git("tag", "t2")

        val deletion = branches.deleteBranches(repo, listOf("merged", "lonely"), force = false)

        assertFalse(deletion.result.success)
        assertContains(deletion.result.message, "the branch 'lonely' is not fully merged")
        assertFalse("hint:" in deletion.result.message, "git's hints point at the command line")
        assertEquals(listOf("lonely"), deletion.notDeleted)
        assertEquals(listOf("lonely", "main"), localBranches())

        val forced = branches.deleteBranches(repo, listOf("lonely"), force = true)

        assertTrue(forced.result.success, forced.result.message)
        assertEquals(emptyList(), forced.notDeleted)
        assertEquals(listOf("main"), localBranches())

        val tagsDeleted = branches.deleteTags(repo, listOf("t1", "t2"))

        assertTrue(tagsDeleted.success, tagsDeleted.message)
        assertEquals(emptyList(), tags())

        val missing = branches.deleteTags(repo, listOf("t1"))
        assertFalse(missing.success)
        assertEquals("error: tag 't1' not found.", missing.message)
    }

    @Test
    fun `refuses names starting with a dash without running git`() {
        val first = commit("First")

        val branch = branches.createBranch(repo, "-x", first, checkout = false, overwrite = false)
        val tag = branches.createTag(repo, "--force", first, message = null)
        val rename = branches.renameBranch(repo, "main", "-m")

        assertEquals(GitResult(false, "'-x' cannot be passed to git: it starts with '-'."), branch)
        assertFalse(tag.success)
        assertFalse(rename.success)
        assertEquals(listOf("main"), localBranches())
        assertEquals(emptyList(), tags())
    }
}
