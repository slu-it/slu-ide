package dev.sluide.git.cli

import dev.sluide.git.GitInProgress
import dev.sluide.git.GitOperation
import dev.sluide.git.GitPendingConflicts
import dev.sluide.git.GitPendingMerge
import dev.sluide.git.GitPendingOperation
import dev.sluide.git.GitPendingPick
import dev.sluide.git.GitPendingRebase
import dev.sluide.git.GitPick
import dev.sluide.git.failingGit
import dev.sluide.git.testGitEnvironment
import dev.sluide.git.unavailableGit
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What `pendingRebase`, `pendingMerge`, `namesOnly` and `inProgress` read: state files that tests write, the real git
 * for the rest.
 */
class GitCliInProgressTest {
    @TempDir
    lateinit var folder: Path

    private lateinit var repository: TestRepository
    private val repo get() = repository.root
    private val state get() = folder.resolve("state")
    private val git = GitCli("git", testGitEnvironment())

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repository = TestRepository(folder.resolve("repo"))
        state.createDirectories()
    }

    private fun stateFile(
        name: String,
        content: String,
    ) = state.resolve(name).writeText(content)

    private fun rebase(apply: Boolean = false) = git.pendingRebase(repo, state, apply)

    @Test
    fun `a rebase reads its branch, base, step and total from the merge backend's files`() {
        stateFile("head-name", "refs/heads/feature\n")
        stateFile("onto", "09af\n")
        stateFile("msgnum", "2\n")
        stateFile("end", "5\n")
        // The apply backend's files stay unread.
        stateFile("next", "7\n")
        stateFile("last", "9\n")

        assertEquals(GitPendingRebase("feature", "09af", null, 2, 5, emptyList()), rebase())
    }

    @Test
    fun `a rebase with the apply backend reads its step and total from next and last`() {
        stateFile("msgnum", "2\n")
        stateFile("end", "5\n")
        stateFile("next", "7\n")
        stateFile("last", "9\n")

        val pending = rebase(apply = true)

        assertEquals(7, pending?.step)
        assertEquals(9, pending?.total)
    }

    @Test
    fun `a rebase leaves what is missing, empty or not of its kind null`() {
        assertEquals(GitPendingRebase(null, null, null, null, null, emptyList()), rebase())

        stateFile("head-name", " \n")
        stateFile("onto", "\n")
        stateFile("msgnum", "")
        stateFile("end", "many\n")
        assertEquals(GitPendingRebase(null, null, null, null, null, emptyList()), rebase())

        // Git writes "detached HEAD" as head-name for a rebase of a detached HEAD.
        stateFile("head-name", "detached HEAD\n")
        assertNull(rebase()?.branch)
        stateFile("head-name", "refs/tags/v1\n")
        assertNull(rebase()?.branch)
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "9", "a", "f", "0123456789abcdef"])
    fun `a rebase base of lowercase hexadecimal digits is kept`(onto: String) {
        stateFile("onto", "$onto\n")

        assertEquals(onto, rebase()?.onto)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/", "`", ":", "g", "A", "8:", "ab/cd", "ab cd", "refs/heads/main"])
    fun `a rebase base with another character is dropped`(onto: String) {
        stateFile("onto", "$onto\n")

        assertNull(rebase()?.onto)
    }

    @Test
    fun `a stopped rebase has the commit REBASE_HEAD names`() {
        val stopped = repository.commit("stopped")
        assertNull(rebase()?.stopped)

        repo.resolve(".git/REBASE_HEAD").writeText("$stopped\n")

        assertEquals(stopped, rebase()?.stopped)
    }

    @Test
    fun `a rebase whose unmerged paths cannot be read is unknown`() {
        assertNull(failingGit(folder, "ls-files").pendingRebase(repo, state, apply = false))
    }

    private fun merge(
        head: String?,
        message: String?,
    ): GitPendingMerge? {
        val mergeHead = folder.resolve("MERGE_HEAD")
        val mergeMessage = folder.resolve("MERGE_MSG")
        mergeHead.deleteIfExists()
        mergeMessage.deleteIfExists()
        head?.let { mergeHead.writeText(it) }
        message?.let { mergeMessage.writeText(it) }
        return git.pendingMerge(repo, mergeHead, mergeMessage)
    }

    @Test
    fun `a merge has the lowercase hexadecimal commits of its MERGE_HEAD, one per line`() {
        val hashes = "0123456789abcdef"

        assertEquals(
            listOf(hashes, "09af", "0", "f", "9", "a"),
            merge("$hashes\n\n  09af  \n0\nf\n9\na\n", null)?.heads,
        )
    }

    @Test
    fun `a merge drops the lines of MERGE_HEAD with another character`() {
        assertEquals(listOf("abc"), merge("/\n`\n:\ng\nA\n8:\nab/cd\nab cd\nabc\n", null)?.heads)
    }

    @Test
    fun `a merge message skips blank lines and lines starting with a hash`() {
        assertEquals(
            "Merge branch 'x' # kept",
            merge(null, "\n   \n# comment\nMerge branch 'x' # kept\nlater\n")?.message,
        )
        assertEquals("a", merge(null, "a\n")?.message)
        assertNull(merge(null, "\n# only\n   \n")?.message)
    }

    @Test
    fun `a merge without its files has no commits and no message`() {
        assertEquals(GitPendingMerge(emptyList(), null, emptyList()), merge(null, null))
    }

    @Test
    fun `a merge whose unmerged paths cannot be read is unknown`() {
        val failing = failingGit(folder, "ls-files")

        assertNull(failing.pendingMerge(repo, folder.resolve("MERGE_HEAD"), folder.resolve("MERGE_MSG")))
    }

    @Test
    fun `a short name stands alone for its full refname`() {
        repository.commit("first")
        repository.git("branch", "topic")

        assertTrue(git.namesOnly(repo, "topic", "refs/heads/topic"))
        assertFalse(git.namesOnly(repo, "topic", "refs/heads/other"))
        assertFalse(git.namesOnly(repo, "missing", "refs/heads/missing"))
    }

    @Test
    fun `a short name that a tag shares is not alone`() {
        repository.commit("first")
        repository.git("branch", "topic")
        repository.git("tag", "topic")

        assertFalse(git.namesOnly(repo, "topic", "refs/heads/topic"))
    }

    @Test
    fun `a name with a leading dash is never alone, though rev-parse would print it`() {
        // `git rev-parse --symbolic-full-name -x` prints "-x" (checked with git 2.56): only the guard says false.
        assertEquals("-x", repository.git("rev-parse", "--symbolic-full-name", "-x"))

        assertFalse(git.namesOnly(repo, "-x", "-x"))
    }

    @Test
    fun `a git that is not available has no rebase count and no names alone`() {
        repository.commit("first")
        repository.git("branch", "topic")
        // Not available, but it would answer: only the guard of each function says no.
        val unavailable = unavailableGit(folder)

        assertNull(unavailable.mergeCount(repo, "refs/heads/topic"))
        assertFalse(unavailable.namesOnly(repo, "topic", "refs/heads/topic"))
        assertEquals(0, git.mergeCount(repo, "refs/heads/topic"))
        assertNull(failingGit(folder, "rev-list").mergeCount(repo, "refs/heads/topic"))
        assertNull(GitCli(folder.resolve("no-such-git").toString(), testGitEnvironment()).mergeCount(repo, "x"))
    }

    private val gitDirectory get() = repo.resolve(".git")

    /** The state of a git that is in the middle of nothing, but for what [setUp] adds. */
    private fun inProgress(
        git: GitCli = this.git,
        setUp: () -> Unit = {},
    ): Result<GitInProgress?> {
        setUp()
        return git.inProgress(repo)
    }

    /** Conflicts in `file.txt`: main and `other` change it differently; returns the commit of `other`. */
    private fun conflicting(): String {
        repository.commit("base")
        repository.git("switch", "-q", "-c", "other")
        val other = repository.commit("other")
        repository.git("switch", "-q", "main")
        repository.commit("main")
        return other
    }

    private fun stops(vararg arguments: String) = repository.gitStops(*arguments)

    @Test
    fun `nothing is in progress in a clean repository, and git that cannot be read is a failure`() {
        repository.commit("base")

        assertEquals(Result.success(null), inProgress())
        assertTrue(inProgress(unavailableGit(folder)).isFailure)
        assertTrue(inProgress(failingGit(folder, "rev-parse")).isFailure)
    }

    @Test
    fun `a rebase with the merge backend is read from rebase-merge`() {
        repository.commit("base")
        val rebaseMerge = gitDirectory.resolve("rebase-merge").createDirectories()
        rebaseMerge.resolve("msgnum").writeText("2\n")
        rebaseMerge.resolve("end").writeText("4\n")
        gitDirectory
            .resolve("rebase-apply")
            .createDirectories()
            .resolve("next")
            .writeText("7\n")

        assertEquals(
            Result.success(GitPendingRebase(null, null, null, 2, 4, emptyList())),
            inProgress(),
        )
    }

    @Test
    fun `a rebase with the apply backend is read from rebase-apply, git am is not a rebase`() {
        repository.commit("base")
        val rebaseApply = gitDirectory.resolve("rebase-apply").createDirectories()
        rebaseApply.resolve("next").writeText("3\n")
        rebaseApply.resolve("last").writeText("5\n")
        rebaseApply.resolve("msgnum").writeText("9\n")

        assertEquals(Result.success(GitPendingRebase(null, null, null, 3, 5, emptyList())), inProgress())

        rebaseApply.resolve("applying").writeText("")

        assertEquals(Result.success(GitPendingOperation(GitOperation.AM)), inProgress())
    }

    @Test
    fun `a rebase whose unmerged paths cannot be read is a failure`() {
        repository.commit("base")
        gitDirectory.resolve("rebase-merge").createDirectories()

        assertTrue(inProgress(failingGit(folder, "ls-files")).isFailure)
    }

    @Test
    fun `a merge is read from MERGE_HEAD, git am wins over it`() {
        val head = repository.commit("base")
        gitDirectory.resolve("MERGE_HEAD").writeText("$head\n")
        gitDirectory.resolve("MERGE_MSG").writeText("Merge branch 'x'\n")

        assertEquals(Result.success(GitPendingMerge(listOf(head), "Merge branch 'x'", emptyList())), inProgress())
        assertTrue(inProgress(failingGit(folder, "ls-files")).isFailure)

        gitDirectory
            .resolve("rebase-apply")
            .createDirectories()
            .resolve("applying")
            .writeText("")

        assertEquals(Result.success(GitPendingOperation(GitOperation.AM)), inProgress())
    }

    @ParameterizedTest
    @CsvSource(
        "pick abc subject,CHERRY_PICK_SEQUENCE",
        "p abc subject,CHERRY_PICK_SEQUENCE",
        "revert abc subject,REVERT_SEQUENCE",
        "'  pick abc',CHERRY_PICK_SEQUENCE",
        "edit abc,SEQUENCE",
        "r abc,SEQUENCE",
        "pickabc,SEQUENCE",
    )
    fun `a sequence is a cherry-pick or a revert as its first instruction says`(
        todo: String,
        expected: GitOperation,
    ) {
        repository.commit("base")
        gitDirectory
            .resolve(
                "sequencer",
            ).createDirectories()
            .resolve("todo")
            .writeText("\n# a comment\n   \n$todo\nrevert zzz\n")

        assertEquals(Result.success(GitPendingOperation(expected)), inProgress())
    }

    @Test
    fun `a sequence without a readable todo list, or only comments, is a sequence of unknown kind`() {
        repository.commit("base")
        val sequencer = gitDirectory.resolve("sequencer").createDirectories()

        assertEquals(Result.success(GitPendingOperation(GitOperation.SEQUENCE)), inProgress())

        sequencer.resolve("todo").writeText("# only\n  # comments\n\n")

        assertEquals(Result.success(GitPendingOperation(GitOperation.SEQUENCE)), inProgress())
    }

    @Test
    fun `a stopped cherry-pick and a stopped revert are read with their conflicts`() {
        val other = conflicting()
        stops("cherry-pick", other)

        assertEquals(Result.success(GitPendingPick(GitPick.CHERRY_PICK, other, listOf("file.txt"))), inProgress())

        repository.git("cherry-pick", "--abort")
        val main = repository.git("rev-parse", "HEAD")
        stops("revert", "HEAD~1")

        assertEquals(
            Result.success(GitPendingPick(GitPick.REVERT, repository.git("rev-parse", "HEAD~1"), listOf("file.txt"))),
            inProgress(),
        )
        assertEquals(main, repository.git("rev-parse", "HEAD"))
        assertTrue(inProgress(failingGit(folder, "ls-files")).isFailure)
    }

    @Test
    fun `conflicts of nothing else in progress are pending conflicts`() {
        val other = conflicting()
        stops("merge", "--squash", other)

        assertEquals(Result.success(GitPendingConflicts(listOf("file.txt"))), inProgress())
        assertTrue(inProgress(failingGit(folder, "ls-files")).isFailure)
    }

    @Test
    fun `a first word that only hashes like pick, p or revert is no cherry-pick or revert`() {
        repository.commit("base")
        // A when over strings checks the hash code first: strings with the same hash code are not the same word.
        val words = listOf("pick" to "ph\u0082k", "p" to "\u0003\u0013", "revert" to "rd\u0095ert")
        val sequencer = gitDirectory.resolve("sequencer").createDirectories()

        for ((word, other) in words) {
            assertEquals(word.hashCode(), other.hashCode())
            sequencer.resolve("todo").writeText("$other abc subject\n")
            assertEquals(Result.success(GitPendingOperation(GitOperation.SEQUENCE)), inProgress(), other)
        }
    }
}
