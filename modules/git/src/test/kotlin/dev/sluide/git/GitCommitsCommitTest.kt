package dev.sluide.git

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [GitCommits.commit] with a git that fails one command or records its calls: what it says when it cannot start
 * or stage, and what it passes to git. Real git; skipped without it.
 */
internal class GitCommitsCommitTest : GitCommitsFixture() {
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

    private fun modified(vararg paths: String) = paths.map { GitChangedFile(GitChange.MODIFIED, it) }

    @Test
    fun `an amend without a commit is refused with its reason, before git commits`() {
        repo.resolve("a.txt").writeText("a\n")
        val recorded = recording()

        val result = recorded.commit(repo, "Message", modified("a.txt"), emptyList(), amend = true)

        assertEquals(GitCommitResult(GitResult(false, "There is no commit to amend."), null), result)
        assertTrue(calls().none { " commit " in " $it " }, calls().toString())
    }

    @Test
    fun `a commit says why it did nothing when HEAD or what is in progress cannot be read`() {
        commit("Base")
        repo.resolve("file.txt").writeText("changed\n")

        val head = failing("rev-parse").commit(repo, "Message", modified("file.txt"), emptyList(), false)
        val busy = failing("--git-path").commit(repo, "Message", modified("file.txt"), emptyList(), false)

        assertEquals(GitCommitResult(GitResult(false, "HEAD could not be read."), null), head)
        assertEquals(GitCommitResult(GitResult(false, IN_PROGRESS_UNREADABLE), null), busy)
    }

    @Test
    fun `untracked files that cannot be staged stop the commit`() {
        val base = commit("Base")
        repo.resolve("new.txt").writeText("new\n")

        val result = failing("add").commit(repo, "Message", emptyList(), listOf("new.txt"), false)

        assertEquals(GitCommitResult(GitResult(false, "fatal: refused by test"), null), result)
        assertEquals(base, head())
    }

    @Test
    fun `untracked files are staged only when there are some, and the list of paths is deleted`() {
        commit("Base")
        repo.resolve("file.txt").writeText("changed\n")
        repo.resolve("new.txt").writeText("new\n")
        val recorded = recording()

        assertTrue(recorded.commit(repo, "Tracked", modified("file.txt"), emptyList(), false).result.success)
        assertTrue(calls().none { " add " in " $it " }, "nothing to stage: ${calls()}")

        assertTrue(recorded.commit(repo, "Both", emptyList(), listOf("new.txt"), false).result.success)

        assertEquals(1, calls().count { " add " in " $it " }, calls().toString())
        val lists = calls().mapNotNull { Regex("--pathspec-from-file=(\\S+)").find(it)?.groupValues?.get(1) }
        assertEquals(3, lists.size, "one for the staging, one for each commit: $lists")
        assertTrue(lists.none { Path.of(it).exists() }, "the temporary lists are deleted: $lists")
    }

    @Test
    fun `an amend of only the message passes no paths to git`() {
        commit("Base")
        val recorded = recording()

        val result = recorded.commit(repo, "Reworded", emptyList(), emptyList(), amend = true)

        assertTrue(result.result.success, result.result.message)
        val call = calls().single { " commit " in " $it " }
        assertFalse("--pathspec-from-file" in call, call)
        assertTrue("--amend" in call, call)
    }
}
