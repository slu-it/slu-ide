package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.branchesContaining
import dev.sluide.git.cli.commitOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Resolving revisions and the branches containing a commit, with the real git in a temporary repository (ADR 0045). */
class GitRevisionsTest {
    @TempDir
    lateinit var repo: Path

    private val git = GitCli("git", testGitEnvironment())
    private val log = GitLog(git)

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        git("init", "-q", "-b", "main")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
    }

    private fun git(
        vararg arguments: String,
        input: String = "",
    ): String {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                }.start()
        process.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
        return output.trim()
    }

    private fun commit(subject: String): String {
        git("commit", "-q", "--allow-empty", "-m", subject)
        return git("rev-parse", "HEAD")
    }

    private fun commitOf(hash: String) = GitRevisionResolution.Commit(hash)

    @Test
    fun `lists the local and remote branches containing a commit, without symbolic refs`() {
        val first = commit("First")
        val second = commit("Second")
        git("branch", "feature", first)
        git("branch", "later", second)
        git("update-ref", "refs/remotes/origin/main", second)
        git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")
        git("tag", "v1", first)
        git("switch", "-q", "--orphan", "other")
        val unrelated = commit("Unrelated")
        git("switch", "-q", "main")

        assertEquals(
            listOf("refs/heads/feature", "refs/heads/later", "refs/heads/main", "refs/remotes/origin/main"),
            log.branchesContaining(repo, first),
        )
        assertEquals(
            listOf("refs/heads/later", "refs/heads/main", "refs/remotes/origin/main"),
            log.branchesContaining(repo, second),
        )
        assertEquals(listOf("refs/heads/other"), log.branchesContaining(repo, unrelated))
        git("branch", "-q", "-D", "other")
        assertEquals(emptyList(), log.branchesContaining(repo, unrelated), "in no branch")
        assertFailsWith<IllegalArgumentException> { log.branchesContaining(repo, "main") }
    }

    @Test
    fun `resolves hashes, prefixes, branches, tags and revision expressions to commits`() {
        val first = commit("First")
        val second = commit("Second")
        val third = commit("Third")
        git("tag", "light", first)
        git("tag", "-a", "-m", "Annotated", "v1.0", second)
        git("branch", "feature", second)
        git("update-ref", "refs/remotes/origin/main", first)
        val tree = git("rev-parse", "HEAD^{tree}")

        val revisions =
            listOf(
                third,
                third.take(7),
                third.uppercase().take(10),
                "feature",
                "refs/heads/feature",
                "origin/main",
                "light",
                "v1.0",
                "v1.0^{}",
                "HEAD~1",
                "main^",
                "nope",
                tree,
                "a b",
            )

        assertEquals(
            listOf(
                commitOf(third),
                commitOf(third),
                commitOf(third),
                commitOf(second),
                commitOf(second),
                commitOf(first),
                commitOf(first),
                commitOf(second),
                commitOf(second),
                commitOf(second),
                commitOf(second),
                GitRevisionResolution.Missing,
                GitRevisionResolution.Missing,
                GitRevisionResolution.Missing,
            ),
            log.resolveCommits(repo, revisions),
        )
        assertEquals(emptyList(), log.resolveCommits(repo, emptyList()))
        git("config", "remote.origin.url", "/nowhere")
        git("config", "remote.origin.fetch", "+refs/heads/*:refs/remotes/origin/*")
        git("config", "branch.main.remote", "origin")
        git("config", "branch.main.merge", "refs/heads/main")
        assertEquals(listOf(commitOf(first)), log.resolveCommits(repo, listOf("@{upstream}")))
    }

    @Test
    fun `a message search resolves to the youngest commit whose message matches`() {
        val fix = commit("Fix the parser")
        commit("Other")

        assertEquals(
            listOf(commitOf(fix), GitRevisionResolution.Missing),
            log.resolveCommits(repo, listOf(":/Fix the", ":/nothing like it")),
        )
    }

    @Test
    fun `a non-ASCII branch name is resolved from the standard input`() {
        val first = commit("First")
        commit("Second")
        git("update-ref", "--stdin", input = "create refs/heads/grün $first\n")

        assertEquals(listOf(commitOf(first)), log.resolveCommits(repo, listOf("grün")))
    }

    @Test
    fun `a prefix of several commits is ambiguous`() {
        // Enough commits for two to share their first four characters.
        val input =
            buildString {
                for (index in 1..2000) {
                    append("commit refs/heads/many\ncommitter A <a@example.com> ${1000 + index} +0000\n")
                    append("data ${"Many $index".length}\nMany $index\n")
                }
            }
        git("fast-import", "--quiet", "--date-format=raw", input = input)
        val prefix =
            git("rev-list", "refs/heads/many")
                .lines()
                .groupBy { it.take(4) }
                .entries
                .first { it.value.size > 1 }
                .key

        assertEquals(
            listOf(GitRevisionResolution.Ambiguous, GitRevisionResolution.Missing),
            log.resolveCommits(repo, listOf(prefix, "f".repeat(39))),
        )
    }

    @Test
    fun `a blank revision, a line break, too much input or a failing git give nothing`() {
        commit("First")
        assertNull(log.resolveCommits(repo, listOf("HEAD", " ")))
        assertNull(log.resolveCommits(repo, listOf("HEAD\nmain")))
        assertNull(log.resolveCommits(repo, List(400) { "a".repeat(40) }))
        // Git cannot even look this up: no upstream is configured.
        assertNull(log.resolveCommits(repo, listOf("@{upstream}")))
        val plain = repo.resolve("plain").createDirectories()
        val outside = GitLog(GitCli("git", testGitEnvironment() + ("GIT_CEILING_DIRECTORIES" to repo.toString())))
        assertNull(outside.resolveCommits(plain, listOf("HEAD")))
        assertNull(outside.branchesContaining(plain, "a".repeat(40)))
    }
}
