package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.inProgress
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the real git in temporary repositories; skipped if git is not installed. */
class GitMergesTest {
    @TempDir
    lateinit var folder: Path

    private val repo get() = folder.resolve("repo")

    private val git = GitCli("git", testGitEnvironment())
    private val merges = GitMerges(git)

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
                    // A commit that concludes a merge would wait for an editor.
                    environment()["GIT_EDITOR"] = ":"
                }.start()
        process.outputStream.close()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
        return output.trim()
    }

    /** Commits [file] with [content] (a line, or several) and [message]; returns the commit's hash. */
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

    private fun rev(revision: String) = git("rev-parse", revision)

    private fun message() = git("log", "-1", "--format=%B")

    private fun file(name: String = "file.txt") = repo.resolve(name).readText()

    private fun pending() = git.inProgress(repo).getOrThrow()

    /**
     * main: Base, Other (other.txt with five lines), Main (changes file.txt if [conflict], else the first line of
     * other.txt); feature from Other: A (changes file.txt). Leaves feature checked out; returns A's hash.
     */
    private fun diverged(conflict: Boolean = false): String {
        commit("Base")
        commit("Other", "other.txt", OTHER)
        git("switch", "-q", "-c", "feature")
        val a = commit("A")
        git("switch", "-q", "main")
        if (conflict) commit("Main") else commit("Main", "other.txt", "main\n2\n3\n4\n5")
        git("switch", "-q", "feature")
        return a
    }

    /** main is ahead of feature by Main (adds main.txt). Leaves feature checked out. */
    private fun behind() {
        commit("Base")
        git("switch", "-q", "-c", "feature")
        git("switch", "-q", "main")
        commit("Main", "main.txt")
        git("switch", "-q", "feature")
    }

    private fun merge(
        mode: GitMergeMode = GitMergeMode.FAST_FORWARD,
        message: String? = null,
        autostash: Boolean = true,
        ref: String = "refs/heads/main",
        branch: String = "feature",
    ) = merges.merge(repo, branch, ref, mode, message, autostash)

    /** Continues the merge in progress; the result without its head, which is checked to be the new `HEAD`. */
    private fun continued(confirmed: Set<GitUncheckedConflict> = emptySet()): GitMergeResult.Done {
        val result = assertIs<GitMergeResult.Done>(merges.continueMerge(repo, confirmed))
        assertEquals(head(), result.head)
        return result.copy(head = null)
    }

    @Test
    fun `fast-forwards a branch that is behind, also with merge_ff=false configured`() {
        git("config", "merge.ff", "false")
        behind()

        assertEquals(GitMergeResult.Done(rev("main"), fastForward = true), merge())
        assertEquals(rev("main"), head())
    }

    @Test
    fun `creates a merge commit with git's message when the branches diverged, also with merge_ff=only configured`() {
        git("config", "merge.ff", "only")
        val a = diverged()

        val result = merge()

        assertEquals(GitMergeResult.Done(head()), result)
        assertEquals(listOf(a, rev("main")), git("log", "-1", "--format=%P").split(' '))
        assertEquals("Merge branch 'main' into feature", message())
        assertNull(pending())
    }

    @Test
    fun `always creates a merge commit when asked, also for a branch that is behind`() {
        behind()
        val before = head()

        assertEquals(GitMergeResult.Done(null), merge(GitMergeMode.MERGE_COMMIT).withoutHead())
        assertEquals(listOf(before, rev("main")), git("log", "-1", "--format=%P").split(' '))
    }

    private fun GitMergeResult.withoutHead() = assertIs<GitMergeResult.Done>(this).copy(head = null)

    @Test
    fun `fast-forward only refuses a merge commit, leaving everything as it was`() {
        diverged()
        val before = head()

        val result = merge(GitMergeMode.FAST_FORWARD_ONLY)

        assertIs<GitMergeResult.Failed>(result)
        assertTrue("fast-forward" in result.message, result.message)
        assertEquals(before, head())
        assertNull(pending())
    }

    @Test
    fun `squash stages the changes without a commit, also with merge_ff=false configured`() {
        git("config", "merge.ff", "false")
        diverged()
        val before = head()

        assertEquals(GitMergeResult.Done(before, squashed = true), merge(GitMergeMode.SQUASH))
        assertEquals(before, head())
        assertEquals("M  other.txt", git("status", "--porcelain"))
        assertFalse(repo.resolve(".git/MERGE_HEAD").exists())
        assertNull(pending())
    }

    @Test
    fun `a squash never stashes, so local changes it does not touch stay on disk`() {
        diverged()
        repo.resolve("file.txt").writeText("local\n")

        assertEquals(GitMergeResult.Done(head(), squashed = true), merge(GitMergeMode.SQUASH, autostash = true))
        assertEquals("local\n", file())
        assertFalse(repo.resolve(".git/MERGE_AUTOSTASH").exists(), "nothing kept stashed until the next commit")
        assertEquals("", git("stash", "list"))
    }

    @Test
    fun `a squash is told apart also when git writes its message in a commit encoding other than UTF-8`() {
        git("config", "i18n.commitEncoding", "ISO-8859-1")
        diverged()
        git("switch", "-q", "main")
        repo.resolve("main.txt").writeText("main\n")
        git("add", "main.txt")
        val message = repo.resolve("message.txt")
        message.writeBytes("Fix für ä".toByteArray(Charsets.ISO_8859_1))
        git("commit", "-q", "-F", message.toString())
        message.toFile().delete()
        git("switch", "-q", "feature")
        val before = head()

        assertEquals(GitMergeResult.Done(before, squashed = true), merge(GitMergeMode.SQUASH))
        assertEquals(before, head())
    }

    @Test
    fun `a squash with conflicts leaves them for the user without a merge in progress`() {
        diverged(conflict = true)

        val result = merge(GitMergeMode.SQUASH)

        assertEquals(GitMergeResult.Done(head(), squashed = true, conflicts = listOf("file.txt")), result)
        assertEquals(GitPendingConflicts(listOf("file.txt")), pending(), "no merge in progress, only its conflicts")
        assertEquals(
            GitMergeResult.Failed("1 file has conflicts; resolve it and mark it resolved first."),
            merge(GitMergeMode.SQUASH),
        )

        // Git refusing the next squash (exit 2, staged changes in the way) is no squash.
        git("add", "file.txt")
        val again = merge(GitMergeMode.SQUASH)
        assertIs<GitMergeResult.Failed>(again)
        assertTrue("overwritten" in again.message, again.message)
    }

    @Test
    fun `nothing runs when the branch is already contained`() {
        diverged()
        git("merge", "-q", "--no-ff", "-m", "Merged", "main")
        val before = head()

        assertEquals(GitMergeResult.UpToDate, merge(GitMergeMode.MERGE_COMMIT))
        assertEquals(before, head())
    }

    @Test
    fun `nothing starts while git is in the middle of something or the branch is no longer current`() {
        diverged(conflict = true)
        assertEquals(GitMergeResult.Failed("'other' is no longer the current branch."), merge(branch = "other"))
        assertEquals(
            GitMergeResult.Failed("'--exec' cannot be passed to git: it starts with '-'."),
            merge(ref = "--exec"),
        )
        val failing = runCatching { git("rebase", "main") }
        assertTrue(failing.isFailure)
        val rebasing = GitMergeResult.Failed("A rebase is in progress; finish or abort it first.")
        assertEquals(rebasing, merge())
        assertEquals(rebasing, merges.continueMerge(repo))
        assertEquals(rebasing, merges.abortMerge(repo))
        git("rebase", "--abort")
        assertEquals(GitMergeResult.Failed("No merge is in progress."), merges.continueMerge(repo))
        assertEquals(GitMergeResult.Failed("No merge is in progress."), merges.abortMerge(repo))

        assertIs<GitMergeResult.Stopped>(merge())
        assertEquals(GitMergeResult.Failed("A merge is in progress; finish or abort it first."), merge())
    }

    @Test
    fun `a conflict stops the merge, which is read with its state, and continuing commits git's message`() {
        diverged(conflict = true)

        val result = merge(GitMergeMode.MERGE_COMMIT)

        assertIs<GitMergeResult.Stopped>(result)
        val expected = GitPendingMerge(listOf(rev("main")), "Merge branch 'main' into feature", listOf("file.txt"))
        assertEquals(expected, result.pending)
        assertEquals(expected, pending())

        assertEquals(GitMergeResult.MarkersLeft(listOf("file.txt")), merges.continueMerge(repo))
        repo.resolve("file.txt").writeText("resolved\n")

        assertEquals(GitMergeResult.Done(null), continued())
        assertEquals("Merge branch 'main' into feature", message(), "without the conflict comments")
        assertEquals("resolved\n", file())
        assertNull(pending())
    }

    @Test
    fun `aborting takes the branch back to before the merge`() {
        diverged(conflict = true)
        val before = head()
        assertIs<GitMergeResult.Stopped>(merge())

        assertEquals(GitMergeResult.Done(before), merges.abortMerge(repo))
        assertEquals("A\n", file())
        assertNull(pending())
    }

    @Test
    fun `a merge started in a terminal is read, an octopus one with all its heads`() {
        commit("Base")
        git("branch", "a")
        git("branch", "b")
        git("switch", "-q", "a")
        val a = commit("A", "a.txt")
        git("switch", "-q", "b")
        val b = commit("B", "b.txt")
        git("switch", "-q", "-c", "feature", "main")
        commit("Feature", "feature.txt")
        git("merge", "-q", "--no-commit", "a", "b")

        assertEquals(GitPendingMerge(listOf(a, b), "Merge branches 'a' and 'b' into feature", emptyList()), pending())

        git("merge", "--abort")
        git("merge", "-q", "--no-commit", "a")
        repo.resolve(".git/MERGE_MSG").deleteExisting()
        assertEquals(GitPendingMerge(listOf(a), null, emptyList()), pending(), "the message cannot be read")
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
        assertIs<GitMergeResult.Stopped>(merge())
        repo.resolve("bin.dat").writeBytes(byteArrayOf(0, 4))
        val unchecked = GitUncheckedConflict("bin.dat", GitConflictReason.BINARY)

        assertEquals(GitMergeResult.Unchecked(listOf(unchecked)), merges.continueMerge(repo))
        assertIs<GitPendingMerge>(pending())

        assertEquals(GitMergeResult.Done(null), continued(setOf(unchecked)))
        assertTrue(repo.resolve("bin.dat").readBytes().contentEquals(byteArrayOf(0, 4)))
    }

    @Test
    fun `a message the user wrote is committed as given, also non-ASCII`() {
        git("config", "i18n.commitEncoding", "ISO-8859-1")
        diverged()
        val text = "Zusammenführung – äöü\n\nMit Text."

        assertEquals(GitMergeResult.Done(null), merge(message = text).withoutHead())
        assertEquals(text, message())
        assertEquals("", git("log", "-1", "--format=%e"), "recorded as UTF-8")
    }

    @Test
    fun `a branch that shares its name with a tag is merged, not the tag, with git's default message`() {
        diverged()
        git("tag", "main", "main~1")

        assertEquals(GitMergeResult.Done(null), merge().withoutHead())
        assertEquals(rev("refs/heads/main"), git("log", "-1", "--format=%P").split(' ')[1])
        assertEquals("Merge branch 'main' into feature", message())
    }

    @ParameterizedTest
    @CsvSource(
        "feature, refs/heads/main, main, Merge branch 'main' into feature",
        "main, refs/heads/feature, feature, Merge branch 'feature'",
        "master, refs/heads/feature, feature, Merge branch 'feature'",
        "x, refs/remotes/origin/main, origin/main, Merge remote-tracking branch 'origin/main' into x",
    )
    fun `the default message is the one git writes`(
        into: String,
        ref: String,
        name: String,
        expected: String,
    ) {
        commit("Base")
        git("branch", "feature")
        git("switch", "-q", "feature")
        commit("Feature", "feature.txt")
        git("switch", "-q", "main")
        commit("Main", "main.txt")
        git("update-ref", "refs/remotes/origin/main", "main")
        if (into != "main") git("switch", "-q", "-C", into, "main~1")
        val start = head()
        git("merge", "-q", "--no-ff", "--no-edit", name)
        val gits = message()
        git("reset", "-q", "--hard", start)

        assertEquals(expected, gits)
        assertEquals(expected, defaultMergeMessage(ref, into))
        assertIs<GitMergeResult.Done>(merge(GitMergeMode.MERGE_COMMIT, ref = ref, branch = into))
        assertEquals(expected, message())
    }

    @Test
    fun `local changes are refused without the autostash, also with merge_autoStash=true configured`() {
        git("config", "merge.autoStash", "true")
        diverged()
        val before = head()
        repo.resolve("other.txt").writeText(LOCAL)

        val result = merge(autostash = false)

        assertIs<GitMergeResult.Failed>(result)
        assertTrue("would be overwritten" in result.message, result.message)
        assertEquals(before, head())
        assertEquals(LOCAL, file("other.txt"))
        assertEquals("", git("stash", "list"))
    }

    @Test
    fun `local changes are stashed and applied after the merge`() {
        diverged()
        repo.resolve("other.txt").writeText(LOCAL)

        assertEquals(GitMergeResult.Done(null), merge().withoutHead())
        assertEquals("main\n2\n3\n4\nlocal\n", file("other.txt"))
        assertEquals("", git("stash", "list"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["continue", "abort", "commit"])
    fun `local changes stashed for a merge that stopped are applied when it ends`(end: String) {
        diverged(conflict = true)
        repo.resolve("other.txt").writeText(LOCAL)
        assertIs<GitMergeResult.Stopped>(merge())
        assertEquals("$OTHER\n", file("other.txt"), "stashed while the merge is in progress")
        repo.resolve("file.txt").writeText("resolved\n")

        when (end) {
            "continue" -> {
                assertEquals(GitMergeResult.Done(null), continued())
            }

            "abort" -> {
                assertEquals(GitMergeResult.Done(null), merges.abortMerge(repo).withoutHead())
            }

            else -> {
                git("add", "file.txt")
                git("commit", "-q", "--no-edit")
            }
        }

        assertEquals(LOCAL, file("other.txt"))
        assertEquals("", git("stash", "list"))
        assertNull(pending())
    }

    @Test
    fun `local changes git cannot apply without conflicts are kept in the stash, the files left with the conflicts`() {
        diverged()
        repo.resolve("other.txt").writeText("local\n2\n3\n4\n5\n")

        assertEquals(GitMergeResult.Done(null, stashKept = true), merge().withoutHead())
        assertEquals(1, git("stash", "list").lines().size)
        assertEquals("UU other.txt", git("status", "--porcelain", "--untracked-files=no"))
        assertTrue(file("other.txt").startsWith("<<<<<<< "), file("other.txt"))
        assertEquals(
            GitPendingConflicts(listOf("other.txt")),
            pending(),
            "no merge in progress, only the conflicts of applying the stash",
        )
    }

    private companion object {
        const val OTHER = "1\n2\n3\n4\n5"
        const val LOCAL = "1\n2\n3\n4\nlocal\n"
    }
}
