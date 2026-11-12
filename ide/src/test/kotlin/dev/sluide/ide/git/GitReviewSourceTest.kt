package dev.sluide.ide.git

import dev.sluide.codereview.ReviewCancellation
import dev.sluide.codereview.ReviewChange
import dev.sluide.codereview.ReviewException
import dev.sluide.codereview.ReviewFileContent
import dev.sluide.codereview.ReviewRevision
import dev.sluide.codereview.ReviewScope
import dev.sluide.fileviewers.LineChange
import dev.sluide.git.GitPulls
import dev.sluide.git.GitRebases
import dev.sluide.git.GitReviews
import dev.sluide.git.cli.GitCli
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The review window's git source on a real temporary repository; skipped if git is not installed. */
class GitReviewSourceTest {
    @TempDir
    lateinit var repo: Path

    /** A second repository, cloned from [repo] and added to it as `origin`. */
    @TempDir
    lateinit var remote: Path

    private val gitEnvironment =
        System.getenv().filterKeys { !it.startsWith("GIT_") } +
            mapOf("GIT_CONFIG_GLOBAL" to "/dev/null", "GIT_CONFIG_NOSYSTEM" to "1")
    private val cli = GitCli("git", gitEnvironment)
    private val source = sourceOf(cli)

    private fun sourceOf(git: GitCli) = GitReviewSource(GitReviews(git), GitPulls(git, GitRebases(git)))

    @BeforeEach
    fun createRepository() {
        assumeTrue(cli.available, "git is not installed")
        git("init", "-q", "-b", "main")
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
    }

    private fun git(vararg arguments: String): String = gitIn(repo, *arguments)

    private fun gitIn(
        directory: Path,
        vararg arguments: String,
    ): String {
        val process =
            ProcessBuilder(listOf("git") + arguments)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(gitEnvironment)
                }.start()
        val output = process.inputStream.readAllBytes().decodeToString()
        assertEquals(0, process.waitFor(), "git ${arguments.joinToString(" ")}: $output")
        return output.trim()
    }

    private fun commit(
        subject: String,
        file: String = "a.txt",
        text: String = "$subject\n",
    ): String {
        repo.resolve(file).writeText(text)
        git("add", "-A")
        git("commit", "-q", "-m", subject)
        return git("rev-parse", "HEAD")
    }

    private fun resolve(
        scope: ReviewScope,
        cancellation: ReviewCancellation = ReviewCancellation(),
    ) = source.resolve(repo, scope, cancellation)

    /** `main` with one commit and `feature` two commits on it; `feature` is checked out. */
    private fun featureBranch(): Triple<String, String, String> {
        val base = commit("base")
        git("checkout", "-q", "-b", "feature")
        val first = commit("first", "b.txt")
        val second = commit("second", "b.txt", "changed\n")
        return Triple(base, first, second)
    }

    @Test
    fun `a branch is reviewed since it left its base`() {
        val (base, first, second) = featureBranch()

        val review = resolve(ReviewScope.Branch("HEAD"))

        assertEquals("feature into main", review.title)
        assertEquals("refs/heads/main", review.base?.ref)
        assertEquals("main", review.base?.label)
        assertTrue(review.base?.isDefault == true)
        assertEquals(base, review.from)
        assertEquals(second, review.head)
        assertEquals(listOf(second, first), review.commits.map { it.hash })
        assertEquals(listOf("second", "first"), review.commits.map { it.subject })
        assertEquals(second.take(7), review.commits.first().shortHash)
        assertEquals(first, review.commits.first().firstParent)
        assertEquals("Test", review.commits.first().author)
        assertEquals("HEAD", review.headRef)
        assertFalse(review.uncommitted)
    }

    @Test
    fun `uncommitted changes count for the checked-out branch only`() {
        featureBranch()
        repo.resolve("b.txt").writeText("edited\n")

        assertTrue(resolve(ReviewScope.Branch("HEAD")).uncommitted)
        assertTrue(resolve(ReviewScope.Branch("refs/heads/feature")).uncommitted)
        git("checkout", "-q", "-f", "main")
        repo.resolve("a.txt").writeText("edited\n")
        assertFalse(resolve(ReviewScope.Branch("refs/heads/feature")).uncommitted)
    }

    @Test
    fun `the bases are the default first, then the other branches`() {
        featureBranch()
        git("branch", "other")

        val bases = source.bases(repo)

        assertEquals(listOf("main", "feature", "other"), bases.map { it.label })
        assertEquals(listOf(true, false, false), bases.map { it.isDefault })
        assertEquals("refs/heads/other", bases.last().ref)
    }

    @Test
    fun `a head without a base is shown by itself`() {
        commit("only")
        git("branch", "-m", "main", "solo")

        val review = resolve(ReviewScope.Branch("HEAD"))

        assertNull(review.base)
        assertNull(review.from)
        assertEquals("solo", review.title)
        assertTrue(review.commits.isEmpty())
    }

    @Test
    fun `the checked-out branch without commits says so, by HEAD or by its name`() {
        for (head in listOf("HEAD", "refs/heads/main")) {
            val failure = assertFailsWith<ReviewException> { resolve(ReviewScope.Branch(head)) }

            assertEquals("The branch has no commits yet.", failure.message)
        }
    }

    @Test
    fun `a deleted branch no longer exists`() {
        featureBranch()
        git("branch", "gone")
        git("branch", "-D", "gone")

        val failure = assertFailsWith<ReviewException> { resolve(ReviewScope.Branch("refs/heads/gone")) }

        assertEquals("The branch gone no longer exists.", failure.message)
    }

    @Test
    fun `a deleted base no longer exists`() {
        featureBranch()
        git("branch", "gone", "main")
        git("branch", "-D", "gone")

        val failure =
            assertFailsWith<ReviewException> { resolve(ReviewScope.Branch("HEAD", base = "refs/heads/gone")) }

        assertEquals("The base gone no longer exists.", failure.message)
    }

    @Test
    fun `a detached HEAD is titled by its short hash`() {
        val (_, _, second) = featureBranch()
        git("checkout", "-q", "--detach")

        val review = resolve(ReviewScope.Branch("HEAD"))

        assertEquals("${second.take(7)} into main", review.title)
        assertEquals("HEAD", review.headRef)
    }

    @Test
    fun `a remote branch is reviewed against its remote's default branch`() {
        commit("base")
        gitIn(repo, "clone", "-q", repo.toString(), remote.toString())
        gitIn(remote, "config", "user.name", "Test")
        gitIn(remote, "config", "user.email", "test@example.com")
        gitIn(remote, "checkout", "-q", "-b", "x")
        remote.resolve("x.txt").writeText("x\n")
        gitIn(remote, "add", "-A")
        gitIn(remote, "commit", "-q", "-m", "on x")
        // The remote's HEAD, which a fetch may record as origin/HEAD, is its default branch.
        gitIn(remote, "checkout", "-q", "main")
        git("remote", "add", "origin", remote.toString())
        git("fetch", "-q", "origin")

        val review = resolve(ReviewScope.Branch("refs/remotes/origin/x"))

        assertEquals("origin/x into origin/main", review.title)
        assertEquals("origin/main", review.base?.label)
        assertEquals(listOf("on x"), review.commits.map { it.subject })
        assertFalse(review.uncommitted)
    }

    @Test
    fun `a branch without a common history with its base says so`() {
        commit("base")
        git("checkout", "-q", "--orphan", "island")
        commit("island")

        val failure = assertFailsWith<ReviewException> { resolve(ReviewScope.Branch("HEAD")) }

        assertEquals("island and main have no common history.", failure.message)
    }

    @Test
    fun `commits on different lines of history are refused`() {
        val base = commit("base")
        git("checkout", "-q", "-b", "left")
        val left = commit("left")
        git("checkout", "-q", "-b", "right", base)
        val right = commit("right")

        val failure = assertFailsWith<ReviewException> { resolve(ReviewScope.Commits(left, right)) }

        assertEquals("The selected commits are not on one line of history.", failure.message)
    }

    @Test
    fun `a range of commits starts at the oldest one's parent`() {
        val base = commit("base")
        val first = commit("first")
        val second = commit("second")

        val review = resolve(ReviewScope.Commits(first, second))

        assertEquals("${first.take(7)}..${second.take(7)}", review.title)
        assertEquals(base, review.from)
        assertEquals(second, review.head)
        assertEquals(listOf(second, first), review.commits.map { it.hash })
        assertNull(review.base)
        assertNull(review.headRef)
        assertFalse(review.uncommitted)
    }

    @Test
    fun `one commit is titled by its hash and subject`() {
        val base = commit("base")
        commit("second")

        val review = resolve(ReviewScope.Commits(base, base))

        assertEquals("${base.take(7)} base", review.title)
    }

    @Test
    fun `a range from the root commit has no parent to start at`() {
        val root = commit("root")
        val next = commit("next")

        val review = resolve(ReviewScope.Commits(root, next))

        assertNull(review.from)
        assertEquals(listOf(next, root), review.commits.map { it.hash })
    }

    @Test
    fun `files and their diffs come back mapped`() {
        val (base, _, second) = featureBranch()
        repo.resolve("c.bin").writeBytes(byteArrayOf(0, 1, 2))
        git("add", "-A")
        git("commit", "-q", "-m", "binary")
        val head = git("rev-parse", "HEAD")
        val cancellation = ReviewCancellation()

        val files = source.changedFiles(repo, base, ReviewRevision.Commit(head), cancellation)

        assertEquals(
            listOf("b.txt" to ReviewChange.ADDED, "c.bin" to ReviewChange.ADDED),
            files.map {
                it.path to
                    it.change
            },
        )
        val diffs = source.fileDiffs(repo, second, ReviewRevision.Commit(head), files.drop(1), false, cancellation)
        assertEquals(setOf("c.bin"), diffs.keys)
        assertEquals(ReviewFileContent.Binary, diffs["c.bin"])
        val text = source.fileDiffs(repo, base, ReviewRevision.Commit(second), files.take(1), false, cancellation)
        assertEquals(
            ReviewFileContent.Text("", "changed\n", listOf(LineChange(0..-1, 0..0))),
            text["b.txt"],
        )
    }

    @Test
    fun `the working tree is the newer side while editing`() {
        val (_, _, second) = featureBranch()
        repo.resolve("b.txt").writeText("edited\n")
        repo.resolve("new.txt").writeText("new\n")

        val files = source.changedFiles(repo, second, ReviewRevision.WorkingTree, ReviewCancellation())

        assertEquals(
            listOf("b.txt" to ReviewChange.MODIFIED, "new.txt" to ReviewChange.ADDED),
            files.map {
                it.path to
                    it.change
            },
        )
    }

    @Test
    fun `the fingerprint changes with a commit on the branch`() {
        featureBranch()
        val review = resolve(ReviewScope.Branch("HEAD"))
        val before = source.fingerprint(repo, review)
        assertNotNull(before)
        assertEquals(before, source.fingerprint(repo, review))

        commit("third", "c.txt")

        assertNotEquals(before, source.fingerprint(repo, review))
    }

    @Test
    fun `the fingerprint changes with an edit only while the branch is checked out`() {
        featureBranch()
        val checkedOut = resolve(ReviewScope.Branch("refs/heads/feature"))
        val before = source.fingerprint(repo, checkedOut)

        repo.resolve("b.txt").writeText("edited\n")
        val after = source.fingerprint(repo, checkedOut)
        assertNotEquals(before, after)

        git("checkout", "-q", "-f", "main")
        repo.resolve("a.txt").writeText("edited\n")
        val elsewhere = source.fingerprint(repo, checkedOut)
        repo.resolve("a.txt").writeText("edited again\n")
        assertEquals(elsewhere, source.fingerprint(repo, checkedOut))
    }

    @Test
    fun `a cancelled load stops quietly`() {
        featureBranch()
        val cancellation = ReviewCancellation().apply { cancel() }

        assertFailsWith<CancellationException> { resolve(ReviewScope.Branch("HEAD"), cancellation) }
        assertFailsWith<CancellationException> {
            source.changedFiles(repo, null, ReviewRevision.WorkingTree, cancellation)
        }
    }

    @Test
    fun `a git failure is a message for the user`() {
        val broken = sourceOf(GitCli(repo.resolve("no-such-git").toString(), gitEnvironment))

        assertFailsWith<ReviewException> {
            broken.changedFiles(repo, null, ReviewRevision.WorkingTree, ReviewCancellation())
        }
        assertTrue(broken.bases(repo).isEmpty())
    }
}
