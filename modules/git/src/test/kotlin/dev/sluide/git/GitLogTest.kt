package dev.sluide.git

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs the real git in a temporary repository; skipped if git is not installed. */
class GitLogTest {
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
        environment: Map<String, String> = emptyMap(),
    ): String {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment() + environment)
                }.start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
        return output.trim()
    }

    /** Commits with the given subject at [time] seconds after the epoch, as author and committer date. */
    private fun commit(
        subject: String,
        time: Long,
    ): String {
        val date = "@$time +0000"
        // The committer date decides the date order.
        val dates = mapOf("GIT_AUTHOR_DATE" to date, "GIT_COMMITTER_DATE" to date)
        git("commit", "-q", "--allow-empty", "-m", subject, environment = dates)
        return git("rev-parse", "HEAD")
    }

    private fun subjects(page: GitLogPage?) = page?.commits?.map { it.subject }

    @Test
    fun `reads the commits of all branches and tags newest first, with parents and author`() {
        val first = commit("First", 1000)
        git("switch", "-q", "-c", "feature")
        val feature = commit("Feature", 3000)
        git("switch", "-q", "main")
        val second = commit("Second", 2000)
        git("tag", "v1", second)
        git("switch", "-q", "--detach", first)
        val tagged = commit("Only tagged", 4000)
        git("tag", "old", tagged)
        git("switch", "-q", "main")

        val page = log.commits(repo, 0, 10)

        assertEquals(listOf("Only tagged", "Feature", "Second", "First"), subjects(page))
        assertTrue(page?.complete == true)
        val commits = page?.commits.orEmpty().associateBy { it.subject }
        assertEquals(listOf(first), commits["Feature"]?.parents)
        assertEquals(feature, commits["Feature"]?.hash)
        assertEquals(emptyList(), commits["First"]?.parents)
        assertEquals("Test", commits["First"]?.author)
        assertEquals("test@example.com", commits["First"]?.authorEmail)
        assertEquals(Instant.ofEpochSecond(1000), commits["First"]?.authorTime)
    }

    @Test
    fun `pages with skip and limit and says when there are no more`() {
        (1..5).forEach { commit("Commit $it", it * 1000L) }

        val first = log.commits(repo, 0, 2)
        val second = log.commits(repo, 2, 2)
        val last = log.commits(repo, 4, 2)

        assertEquals(listOf("Commit 5", "Commit 4"), subjects(first))
        assertFalse(first?.complete == true)
        assertEquals(listOf("Commit 3", "Commit 2"), subjects(second))
        assertFalse(second?.complete == true)
        assertEquals(listOf("Commit 1"), subjects(last))
        assertTrue(last?.complete == true)
        assertEquals(GitLogPage(emptyList(), complete = true), log.commits(repo, 5, 2))
    }

    @Test
    fun `leaves out stashes and includes a detached HEAD`() {
        repo.resolve("file.txt").writeText("one\n")
        git("add", "file.txt")
        val base = commit("Base", 1000)
        repo.resolve("file.txt").writeText("two\n")
        git("stash", "-q")
        git("switch", "-q", "--detach", base)
        commit("Detached", 2000)

        assertEquals(listOf("Detached", "Base"), subjects(log.commits(repo, 0, 10)))
    }

    @Test
    fun `reads subjects and authors as UTF-8 whatever the configured log output encoding`() {
        git("config", "i18n.logOutputEncoding", "ISO-8859-1")
        // Through files, as UTF-8: arguments of started processes take the JVM's locale, which may be plain ASCII.
        repo.resolve(".git/config").appendText("[user]\n\tname = Jürgen\n")
        val message = repo.resolve(".git/message").apply { writeText("Größe\n") }
        git("commit", "-q", "--allow-empty", "-F", message.toString())

        val commit = log.commits(repo, 0, 10)?.commits?.single()

        assertEquals("Größe", commit?.subject)
        assertEquals("Jürgen", commit?.author)
    }

    @Test
    fun `applies the mailmap to authors`() {
        repo.resolve(".mailmap").writeText("Real Name <real@example.com> <test@example.com>\n")
        commit("Mapped", 1000)

        val commit = log.commits(repo, 0, 10)?.commits?.single()

        assertEquals("Real Name", commit?.author)
        assertEquals("real@example.com", commit?.authorEmail)
    }

    @Test
    fun `a repository without commits has an empty, complete first page, also with a file named HEAD`() {
        repo.resolve("HEAD").writeText("")

        assertEquals(GitLogPage(emptyList(), complete = true), log.commits(repo, 0, 10))
    }

    @Test
    fun `an unborn branch still shows the other branches`() {
        commit("On main", 1000)
        git("switch", "-q", "--orphan", "fresh")

        assertEquals(listOf("On main"), subjects(log.commits(repo, 0, 10)))
    }

    @Test
    fun `reads branches, remote branches and lightweight and annotated tags, without symbolic refs`() {
        val first = commit("First", 1000)
        val second = commit("Second", 2000)
        git("tag", "light", first)
        git("tag", "-a", "-m", "Annotated", "annotated", second)
        git("branch", "feature", first)
        git("update-ref", "refs/remotes/origin/main", first)
        git("symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/main")

        val refs = log.refs(repo)

        assertEquals("refs/heads/main", refs?.currentBranch)
        assertEquals(listOf("HEAD", "main", "annotated"), refs?.at(second)?.map { it.shortName })
        assertEquals(listOf("feature", "origin/main", "light"), refs?.at(first)?.map { it.shortName })
        assertEquals(
            listOf(GitRefKind.LOCAL_BRANCH, GitRefKind.REMOTE_BRANCH, GitRefKind.TAG),
            refs?.at(first)?.map { it.kind },
        )
        assertEquals(6, refs?.refs?.size, "no refs/remotes/origin/HEAD: ${refs?.refs}")
    }

    @Test
    fun `a detached HEAD has no current branch`() {
        val first = commit("First", 1000)
        commit("Second", 2000)
        git("switch", "-q", "--detach", first)

        val refs = log.refs(repo)

        assertNull(refs?.currentBranch)
        assertEquals(listOf(GitRef("HEAD", GitRefKind.HEAD, first)), refs?.at(first))
    }

    @Test
    fun `a repository without commits has no refs and no HEAD`() {
        assertEquals(GitRefs(emptyList(), "refs/heads/main"), log.refs(repo))
    }

    @Test
    fun `reads the details of a commit with a multi-line message and another committer`() {
        val message = repo.resolve(".git/message").apply { writeText("Subject line\n\nFirst body line.\nSecond.\n") }
        val environment =
            mapOf(
                "GIT_AUTHOR_DATE" to "@1000 +0000",
                "GIT_COMMITTER_DATE" to "@2000 +0000",
                "GIT_COMMITTER_NAME" to "Committer",
                "GIT_COMMITTER_EMAIL" to "committer@example.com",
            )
        val parent = commit("Parent", 500)
        git("commit", "-q", "--allow-empty", "-F", message.toString(), environment = environment)
        val hash = git("rev-parse", "HEAD")

        val details = log.details(repo, hash)

        assertEquals(hash, details?.commit?.hash)
        assertEquals(listOf(parent), details?.commit?.parents)
        assertEquals("Subject line", details?.commit?.subject)
        assertEquals("Test", details?.commit?.author)
        assertEquals("test@example.com", details?.commit?.authorEmail)
        assertEquals(Instant.ofEpochSecond(1000), details?.commit?.authorTime)
        assertEquals("Committer", details?.committer)
        assertEquals("committer@example.com", details?.committerEmail)
        assertEquals(Instant.ofEpochSecond(2000), details?.committerTime)
        assertEquals("Subject line\n\nFirst body line.\nSecond.", details?.message)
    }

    @Test
    fun `details of an unknown commit fail, and only hashes are accepted`() {
        commit("First", 1000)

        assertNull(log.details(repo, "0".repeat(40)))
        assertFailsWith<IllegalArgumentException> { log.details(repo, "HEAD") }
        assertFailsWith<IllegalArgumentException> { log.details(repo, "abc") }
        val hash = git("rev-parse", "HEAD")
        assertFailsWith<IllegalArgumentException>("abbreviated") { log.details(repo, hash.take(12)) }
        assertFailsWith<IllegalArgumentException> { log.details(repo, "${hash}0") }
        assertFailsWith<IllegalArgumentException> { log.details(repo, "--all") }
    }

    @Test
    fun `fails outside a repository and without git`() {
        val plain = repo.resolve("plain").createDirectories()
        val outside = GitLog(GitCli("git", testGitEnvironment() + ("GIT_CEILING_DIRECTORIES" to repo.toString())))
        assertNull(outside.commits(plain, 0, 10))
        assertNull(GitLog(GitCli(repo.resolve("no-such-git").toString(), System.getenv())).commits(repo, 0, 10))
        assertNull(outside.refs(plain))
        assertNull(outside.details(plain, "a".repeat(40)))
    }
}
