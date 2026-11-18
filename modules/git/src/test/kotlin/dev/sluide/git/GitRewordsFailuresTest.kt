package dev.sluide.git

import dev.sluide.git.cli.GitCli
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a reword does when one of the git commands it runs fails, with the real git behind a wrapper. */
class GitRewordsFailuresTest {
    @TempDir
    lateinit var folder: Path

    private val repo get() = folder.resolve("repo")

    private val git = GitCli("git", testGitEnvironment())

    private lateinit var target: String
    private lateinit var short: String

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo.createDirectories()
        git("init", "-q", "-b", "feature")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
        commit("Base")
        target = commit("Target", "t.txt")
        commit("Later", "l.txt")
        short = target.take(8)
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

    private fun commit(
        message: String,
        file: String = "file.txt",
    ): String {
        repo.resolve(file).writeText("$message\n")
        git("add", file)
        git("commit", "-q", "-m", message)
        return git("rev-parse", "HEAD")
    }

    private fun tip() = git("rev-parse", "refs/heads/feature")

    /** Both the dialog's check and the reword refuse with [reason] when git fails as [failing] makes it, nothing moves. */
    private fun assertRefused(
        reason: String,
        failing: () -> GitCli,
    ) {
        val before = tip()

        assertEquals(GitRewordCheck.Refused(reason), GitRewords(failing()).check(repo, "feature", target), "check")
        assertEquals(
            GitRewordResult.Failed(reason),
            GitRewords(failing()).reword(repo, "feature", target, "New"),
            "reword",
        )
        assertEquals(before, tip())
    }

    // Each word below is one command the plan runs: `rev-parse --git-path` (what is in progress), `rev-parse
    // --verify` (HEAD), `merge-base --is-ancestor`, `remote`, `for-each-ref` twice (the refs, then the branches that
    // contain the commit), `rev-list --ancestry-path`.
    @Test
    fun `refuses when what git is in the middle of cannot be read`() {
        assertRefused(IN_PROGRESS_UNREADABLE) { failingGit(folder, "--git-path") }
    }

    @Test
    fun `refuses a commit that is not on the branch when HEAD cannot be read`() {
        assertRefused("'$short' is not on the current branch 'feature'.") { failingGit(folder, "--verify") }
    }

    @Test
    fun `refuses when it cannot be read whether the commit is on the branch`() {
        assertRefused("Whether '$short' is on the current branch 'feature' could not be read.") {
            failingGit(folder, "merge-base")
        }
    }

    @Test
    fun `refuses when the default branch cannot be found out`() {
        val reason = "Whether '$short' is on the default branch could not be read."

        assertRefused(reason) { failingGit(folder, "remote") }
        assertRefused(reason) { failingNthGit(folder, "for-each-ref", 1) }
        assertRefused(reason) { failingNthGit(folder, "for-each-ref", 2) }
    }

    @Test
    fun `refuses when the commits after it cannot be read`() {
        assertRefused("The commits after '$short' could not be read.") { failingGit(folder, "rev-list") }
    }

    @Test
    fun `check refuses when the message cannot be read`() {
        val rewords = GitRewords(failingGit(folder, "show"))

        assertEquals(
            GitRewordCheck.Refused("The message of '$short' could not be read."),
            rewords.check(repo, "feature", target),
        )
    }

    @Test
    fun `reword fails without moving the branch when a step fails`() {
        val before = tip()
        val failures =
            mapOf(
                "stripspace" to "The message could not be cleaned up.",
                "show" to "The message of '$short' could not be read.",
                "cat-file" to "The commits from '$short' on could not be read.",
            )
        for ((word, reason) in failures) {
            val result = GitRewords(failingGit(folder, word)).reword(repo, "feature", target, "New")

            assertEquals(GitRewordResult.Failed(reason), result, word)
        }
        // The old message is cleaned up second.
        assertEquals(
            GitRewordResult.Failed("The message of '$short' could not be read."),
            GitRewords(failingNthGit(folder, "stripspace", 2)).reword(repo, "feature", target, "New"),
        )
        assertEquals(before, tip())
    }

    @Test
    fun `reword fails with git's message when the committer is unknown or a commit cannot be written`() {
        val before = tip()
        val committer = GitRewords(failingGit(folder, "var")).reword(repo, "feature", target, "New")
        val written = GitRewords(failingGit(folder, "hash-object")).reword(repo, "feature", target, "New")
        val later = GitRewords(failingNthGit(folder, "hash-object", 2)).reword(repo, "feature", target, "New")

        assertEquals(GitRewordResult.Failed("fatal: refused by test"), committer)
        assertTrue(written is GitRewordResult.Failed && "refused by test" in written.message, written.toString())
        assertTrue(later is GitRewordResult.Failed && "refused by test" in later.message, later.toString())
        assertEquals(before, tip())
    }

    @Test
    fun `the reflog of the branch names the new subject, or the commit if the subject cannot be passed to git`() {
        val rewords = GitRewords(git)

        rewords.reword(repo, "feature", target, "New subject\n\nBody.")

        assertEquals("reword: New subject", git("reflog", "-1", "--format=%gs", "refs/heads/feature"))

        // In a plain ASCII locale the JVM cannot pass non-ASCII text as an argument.
        val later = git("rev-parse", "HEAD")
        rewords.canPass = { false }
        rewords.reword(repo, "feature", later, "Another subject")

        assertEquals("reword ${later.take(8)}", git("reflog", "-1", "--format=%gs", "refs/heads/feature"))
    }
}
