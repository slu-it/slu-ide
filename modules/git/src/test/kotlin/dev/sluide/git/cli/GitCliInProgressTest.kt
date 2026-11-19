package dev.sluide.git.cli

import dev.sluide.git.GitPendingMerge
import dev.sluide.git.GitPendingRebase
import dev.sluide.git.failingGit
import dev.sluide.git.testGitEnvironment
import dev.sluide.git.unavailableGit
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
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

/** What `pendingRebase`, `pendingMerge` and `namesOnly` read: state files that tests write, the real git for the rest. */
class GitCliInProgressTest {
    @TempDir
    lateinit var folder: Path

    private val repo get() = folder.resolve("repo")
    private val state get() = folder.resolve("state")
    private val git = GitCli("git", testGitEnvironment())

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo.createDirectories()
        state.createDirectories()
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
                }.start()
        process.outputStream.close()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
        return output.trim()
    }

    private fun commit(message: String): String {
        repo.resolve("file.txt").writeText("$message\n")
        git("add", "file.txt")
        git("commit", "-q", "-m", message)
        return git("rev-parse", "HEAD")
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
        val stopped = commit("stopped")
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
        commit("first")
        git("branch", "topic")

        assertTrue(git.namesOnly(repo, "topic", "refs/heads/topic"))
        assertFalse(git.namesOnly(repo, "topic", "refs/heads/other"))
        assertFalse(git.namesOnly(repo, "missing", "refs/heads/missing"))
    }

    @Test
    fun `a short name that a tag shares is not alone`() {
        commit("first")
        git("branch", "topic")
        git("tag", "topic")

        assertFalse(git.namesOnly(repo, "topic", "refs/heads/topic"))
    }

    @Test
    fun `a name with a leading dash is never alone, though rev-parse would print it`() {
        // `git rev-parse --symbolic-full-name -x` prints "-x" (checked with git 2.56): only the guard says false.
        assertEquals("-x", git("rev-parse", "--symbolic-full-name", "-x"))

        assertFalse(git.namesOnly(repo, "-x", "-x"))
    }

    @Test
    fun `a git that is not available has no rebase count and no names alone`() {
        commit("first")
        git("branch", "topic")
        // Not available, but it would answer: only the guard of each function says no.
        val unavailable = unavailableGit(folder)

        assertNull(unavailable.mergeCount(repo, "refs/heads/topic"))
        assertFalse(unavailable.namesOnly(repo, "topic", "refs/heads/topic"))
        assertEquals(0, git.mergeCount(repo, "refs/heads/topic"))
        assertNull(failingGit(folder, "rev-list").mergeCount(repo, "refs/heads/topic"))
        assertNull(GitCli(folder.resolve("no-such-git").toString(), testGitEnvironment()).mergeCount(repo, "x"))
    }
}
