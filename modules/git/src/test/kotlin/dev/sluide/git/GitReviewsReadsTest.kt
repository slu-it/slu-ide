package dev.sluide.git

import dev.sluide.git.cli.GitCli
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * What the review window's reads give when git fails, for refs that are not the usual ones, and at the limits of its
 * arguments (ADR 0168), with the real git behind a wrapper that fails; skipped without git.
 */
internal class GitReviewsReadsTest : GitLogFixture() {
    private val reviews = GitReviews(git)

    // The wrapper script lives in .git, where it is no untracked file.
    private fun failing(vararg words: String) = GitReviews(failingGit(repo.resolve(".git"), *words))

    private fun commitSide(hash: String) = GitReviewSide.Commit(hash)

    @Test
    fun `the default bases and the branches are empty when git cannot list the refs or the remotes`() {
        commit("First", 1000)
        git("update-ref", "refs/remotes/origin/main", "HEAD")
        git("remote", "add", "origin", "https://example.com/origin.git")
        assertEquals(listOf("refs/remotes/origin/main", "refs/heads/main"), reviews.defaultBases(repo))
        assertEquals(listOf("refs/heads/main", "refs/remotes/origin/main"), reviews.branches(repo))

        assertEquals(emptyList(), failing("for-each-ref").defaultBases(repo))
        assertEquals(emptyList(), failing("remote").defaultBases(repo))
        assertEquals(emptyList(), failing("for-each-ref").branches(repo))
    }

    @Test
    fun `a detached HEAD or a branch that does not exist has no upstream, a remote branch is its own remote's`() {
        val hash = commit("First", 1000)
        for (remote in listOf("alpha", "origin", "zeta")) {
            git("remote", "add", remote, "https://example.com/$remote.git")
            git("update-ref", "refs/remotes/$remote/main", hash)
        }
        git("branch", "-q", "--track", "tracking", "zeta/main")
        val origin = listOf("refs/remotes/origin/main", "refs/remotes/alpha/main", "refs/remotes/zeta/main")

        git("switch", "-q", "--detach")
        assertEquals(origin, reviews.defaultBases(repo).take(3), "detached")
        assertEquals(origin, reviews.defaultBases(repo, "refs/heads/none").take(3), "no such branch")
        assertEquals(
            listOf("refs/remotes/zeta/main", "refs/remotes/origin/main", "refs/remotes/alpha/main"),
            reviews.defaultBases(repo, "refs/heads/tracking").take(3),
            "upstream of a local branch first",
        )
        assertEquals(
            listOf("refs/remotes/alpha/main", "refs/remotes/origin/main", "refs/remotes/zeta/main"),
            reviews.defaultBases(repo, "refs/remotes/alpha/main").take(3),
            "the remote of a remote branch first",
        )
    }

    @Test
    fun `a ref called HEAD below refs_remotes is no branch, one below refs_heads or ending otherwise is`() {
        val hash = commit("First", 1000)
        for (ref in listOf("remotes/o/HEAD", "remotes/o/x/HEAD", "remotes/o/AHEAD", "heads/x/HEAD", "remotes/o/main")) {
            git("update-ref", "refs/$ref", hash)
        }

        assertEquals(
            listOf("refs/heads/main", "refs/heads/x/HEAD", "refs/remotes/o/AHEAD", "refs/remotes/o/main"),
            reviews.branches(repo),
        )
    }

    @Test
    fun `the limit of commits must be positive, one is allowed, and the messages say what is wrong`() {
        val hash = commit("First", 1000)
        commit("Second", 2000)
        val second = git("rev-parse", "HEAD")

        val limit = assertFailsWith<IllegalArgumentException> { reviews.commits(repo, null, hash, limit = 0) }
        assertEquals("limit must be positive: 0", limit.message)
        assertEquals(1, reviews.commits(repo, null, second, limit = 1)?.size)
        val from = assertFailsWith<IllegalArgumentException> { reviews.commits(repo, "HEAD", second) }
        assertEquals("not a commit hash: HEAD", from.message)
        val to = assertFailsWith<IllegalArgumentException> { reviews.commits(repo, null, "main") }
        assertEquals("not a commit hash: main", to.message)
    }

    @Test
    fun `files and diffs take only full hashes on both sides`() {
        val hash = commitFiles("First", "a.txt" to "a\n")
        val file = listOf(GitChangedFile(GitChange.MODIFIED, "a.txt"))
        val side = commitSide(hash)

        for (bad in listOf("HEAD", hash.take(12))) {
            assertFailsWith<IllegalArgumentException>(bad) { reviews.fileDiffs(repo, bad, side, file, false) }
            assertFailsWith<IllegalArgumentException>(bad) {
                reviews.fileDiffs(repo, null, commitSide(bad), file, false)
            }
            assertFailsWith<IllegalArgumentException>(
                bad,
            ) { reviews.fileDiffs(repo, bad, GitReviewSide.WorkingTree, file, false) }
            assertFailsWith<IllegalArgumentException>(
                bad,
            ) { reviews.changedFiles(repo, bad, GitReviewSide.WorkingTree) }
        }
        assertNotNull(reviews.fileDiffs(repo, hash, side, file, false))
    }

    @Test
    fun `a read that is not cancelled gives its answer, with a cancellation at hand`() {
        val from = commitFiles("First", "a.txt" to "a\n")
        val to = commitFiles("Second", "a.txt" to "b\n")
        val files = listOf(GitChangedFile(GitChange.MODIFIED, "a.txt"))
        repo.resolve("a.txt").writeText("c\n")

        for (side in listOf(commitSide(to), GitReviewSide.WorkingTree)) {
            val live = GitCancellation()

            assertEquals(files, reviews.changedFiles(repo, from, side, live), "$side")
            assertEquals(setOf("a.txt"), reviews.fileDiffs(repo, from, side, files, false, live)?.keys, "$side")
        }
    }

    @Test
    fun `the working tree's files and diffs are unknown when one of the git reads fails`() {
        val from = commitFiles("First", "a.txt" to "a\n")
        repo.resolve("a.txt").writeText("b\n")
        repo.resolve("new.txt").writeText("new\n")
        val files = listOf(GitChangedFile(GitChange.MODIFIED, "a.txt"))
        val side = GitReviewSide.WorkingTree

        assertNull(failing("--name-status").changedFiles(repo, from, side), "the tracked changes")
        assertNull(failing("--others").changedFiles(repo, from, side), "the untracked files")
        assertNull(failing("hash-object").changedFiles(repo, null, side), "the empty tree")
        assertNull(failing("hash-object").fileDiffs(repo, null, side, files, false), "the empty tree of the diffs")
    }

    @Test
    fun `the diffs of commits are unknown when the sizes or the contents cannot be read`() {
        val from = commitFiles("First", "a.txt" to "a\n")
        val to = commitFiles("Second", "a.txt" to "b\n")
        val files = listOf(GitChangedFile(GitChange.MODIFIED, "a.txt"))

        assertNull(failing("--batch-check").fileDiffs(repo, from, commitSide(to), files, false), "the sizes")
        assertNull(failing("--batch").fileDiffs(repo, from, commitSide(to), files, false), "the contents")
        assertNull(failing("--name-status").changedFiles(repo, from, commitSide(to)), "the files")
    }

    @Test
    fun `a renamed file whose old path has a line break is left out`() {
        val from = commitFiles("First", "a.txt" to "a\n")
        val to = commitFiles("Second", "a.txt" to "b\n")
        val files = listOf(GitChangedFile(GitChange.RENAMED, "a.txt", oldPath = "x\ny.txt"))

        assertEquals(emptyMap(), reviews.fileDiffs(repo, from, commitSide(to), files, ignoreWhitespace = false))
    }

    @Test
    fun `a file of exactly the largest size is compared, one byte more is too large`() {
        val from = commitFiles("First", "same.txt" to "1234567890", "more.txt" to "1234567890")
        val to = commitFiles("Second", "same.txt" to "abcdefghij", "more.txt" to "abcdefghijk")
        reviews.maxSize = 10
        val files =
            listOf(GitChangedFile(GitChange.MODIFIED, "same.txt"), GitChangedFile(GitChange.MODIFIED, "more.txt"))

        val diffs = reviews.fileDiffs(repo, from, commitSide(to), files, ignoreWhitespace = false)

        assertEquals(
            GitFileDiff.Text("1234567890", "abcdefghij", listOf(GitHunk(0 until 1, 0 until 1))),
            diffs?.get("same.txt"),
        )
        assertEquals(GitFileDiff.TooLarge, diffs?.get("more.txt"))
    }

    @Test
    fun `the fingerprint is unknown when the commits or the status cannot be read, and the same for no revisions`() {
        commitFiles("First", "a.txt" to "a\n")
        repo.resolve("a.txt").writeText("b\n")

        assertNull(failing("--batch-check").fingerprint(repo, listOf("HEAD"), workingTree = false))
        assertNull(failing("status").fingerprint(repo, listOf("HEAD"), workingTree = true))
        // The SHA-256 of nothing at all.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            reviews.fingerprint(repo, emptyList(), workingTree = false),
        )
        assertNotEquals(reviews.fingerprint(repo, emptyList(), false), reviews.fingerprint(repo, emptyList(), true))
    }

    @Test
    fun `the working tree's fingerprint tells the status, the size and a missing file`() {
        commitFiles("First", "a.txt" to "a\n")
        val file = repo.resolve("a.txt")
        val time = FileTime.from(Instant.ofEpochSecond(1_000_000))
        file.writeText("changed\n")
        Files.setLastModifiedTime(file, time)
        val unstaged = reviews.fingerprint(repo, emptyList(), workingTree = true)

        // The same size and time, another status.
        git("add", "a.txt")
        val staged = reviews.fingerprint(repo, emptyList(), workingTree = true)
        assertNotEquals(unstaged, staged, "the status")

        // The same status and time, another size.
        file.writeText("changed!\n")
        Files.setLastModifiedTime(file, time)
        git("add", "a.txt")
        file.writeText("changed!!\n")
        Files.setLastModifiedTime(file, time)
        val longer = reviews.fingerprint(repo, emptyList(), workingTree = true)
        file.writeText("changed!?\n")
        Files.setLastModifiedTime(file, time)
        assertEquals(longer, reviews.fingerprint(repo, emptyList(), workingTree = true), "the same size")
        file.writeText("changed!??\n")
        Files.setLastModifiedTime(file, time)
        assertNotEquals(longer, reviews.fingerprint(repo, emptyList(), workingTree = true), "the size")

        val present = reviews.fingerprint(repo, emptyList(), workingTree = true)
        file.deleteExisting()
        assertNotEquals(present, reviews.fingerprint(repo, emptyList(), workingTree = true), "the file is gone")
    }

    @Test
    fun `a symbolic ref of any name is no branch`() {
        val hash = commit("First", 1000)
        git("update-ref", "refs/remotes/o/main", hash)
        git("symbolic-ref", "refs/remotes/o/alias", "refs/remotes/o/main")
        git("symbolic-ref", "refs/heads/local-alias", "refs/heads/main")

        assertEquals(listOf("refs/heads/main", "refs/remotes/o/main"), reviews.branches(repo))
    }

    @Test
    fun `the fingerprint of no revisions asks git for nothing, the status of a failing git is none`() {
        val noGit = GitReviews(GitCli(repo.resolve("no-such-git").toString(), System.getenv()))

        // The SHA-256 of nothing at all: no revision, no working tree, no git needed.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            noGit.fingerprint(repo, emptyList(), workingTree = false),
        )
        assertNull(noGit.fingerprint(repo, listOf("HEAD"), workingTree = false))
        assertNull(failing("status").hasUncommittedChanges(repo))
    }

    @Test
    fun `a file listed as modified in a range from the empty tree is compared with nothing`() {
        val to = commitFiles("First", "a.txt" to "a\n")

        val diffs =
            reviews.fileDiffs(repo, null, commitSide(to), listOf(GitChangedFile(GitChange.MODIFIED, "a.txt")), false)

        assertEquals(mapOf("a.txt" to GitFileDiff.Text("", "a\n", listOf(GitHunk(0 until 0, 0 until 1)))), diffs)
    }

    @Test
    fun `a file whose hunks cannot be computed is left out, not given as nothing`() {
        val from = commitFiles("First", "a.txt" to "a\n")
        val to = commitFiles("Second", "a.txt" to "b\n")
        val files = listOf(GitChangedFile(GitChange.MODIFIED, "a.txt"))

        val diffs = failing("diff").fileDiffs(repo, from, commitSide(to), files, ignoreWhitespace = false)

        assertEquals(emptyMap(), diffs)
    }

    @Test
    fun `a file missing on only one side of the range is left out, whichever side`() {
        val from = commitFiles("First", "a.txt" to "a\n")
        val to = commitFiles("Second", "a.txt" to null, "b.txt" to "b\n")
        val onlyInTo = GitChangedFile(GitChange.MODIFIED, "b.txt")
        val onlyInFrom = GitChangedFile(GitChange.MODIFIED, "a.txt")

        val diffs = reviews.fileDiffs(repo, from, commitSide(to), listOf(onlyInTo, onlyInFrom), false)

        assertEquals(emptyMap(), diffs)
    }

    @Test
    fun `the files of the working tree are sorted by path, the untracked ones among the tracked`() {
        commitFiles("First", "a.txt" to "a\n", "c.txt" to "c\n")
        repo.resolve("a.txt").writeText("changed\n")
        repo.resolve("c.txt").writeText("changed\n")
        repo.resolve("b.txt").writeText("new\n")

        val files = reviews.changedFiles(repo, git("rev-parse", "HEAD"), GitReviewSide.WorkingTree)

        assertEquals(
            listOf(
                GitChangedFile(GitChange.MODIFIED, "a.txt"),
                GitChangedFile(GitChange.ADDED, "b.txt"),
                GitChangedFile(GitChange.MODIFIED, "c.txt"),
            ),
            files,
        )
    }

    private fun recordingReviews(): GitReviews =
        GitReviews(recordingGit(repo.resolve(".git"), repo.resolve(".git/calls.log")))

    /** The blobs are read by `git cat-file --batch`; `--batch-check` only sizes them. */
    private fun blobReads() = recordedCalls(repo.resolve(".git/calls.log")).count { it.endsWith("cat-file --batch") }

    @Test
    fun `a file above the largest size is not read at all`() {
        val from = commitFiles("First", "big.txt" to "small\n")
        val to = commitFiles("Second", "big.txt" to "now larger than ten bytes\n")
        val reviews = recordingReviews()
        reviews.maxSize = 10
        val files = listOf(GitChangedFile(GitChange.MODIFIED, "big.txt"))

        val diffs = reviews.fileDiffs(repo, from, commitSide(to), files, ignoreWhitespace = false)

        assertEquals(mapOf("big.txt" to GitFileDiff.TooLarge), diffs)
        assertEquals(0, blobReads(), "no blob is read")
    }

    @Test
    fun `a submodule is not read at all`() {
        git("update-index", "--add", "--cacheinfo", "160000,${"1".repeat(40)},lib")
        val from = commit("First", 1000)
        git("update-index", "--add", "--cacheinfo", "160000,${"2".repeat(40)},lib")
        val to = commit("Second", 2000)
        val reviews = recordingReviews()
        val files = listOf(GitChangedFile(GitChange.MODIFIED, "lib"))

        val diffs = reviews.fileDiffs(repo, from, commitSide(to), files, ignoreWhitespace = false)

        assertEquals(mapOf("lib" to GitFileDiff.Submodule("1".repeat(40), "2".repeat(40))), diffs)
        assertEquals(0, blobReads(), "no blob is read")
    }

    @Test
    fun `diffs of the working tree are none when the read was cancelled while git filtered the file`() {
        val from = commitFiles("First", "a.txt" to "a\n")
        repo.resolve("a.txt").writeText("b\n")
        val started = repo.resolve(".git/filters-started")
        val go = repo.resolve(".git/filters-go")
        // The filtered read of the file (no cancellation reaches it) waits until the test has cancelled.
        val script = repo.resolve(".git/slow-filters-git")
        script.writeText(
            FAKE_GIT_START +
                "case \" \$* \" in *' --filters '*) touch '$started'; i=0\n" +
                "while [ ! -e '$go' ] && [ \$i -lt 400 ]; do sleep 0.05; i=\$((i+1)); done;; esac\n" +
                "exec git \"\$@\"\n",
        )
        script.toFile().setExecutable(true)
        val slow = GitReviews(GitCli(script.toString(), testGitEnvironment()))
        val cancellation = GitCancellation()
        val files = listOf(GitChangedFile(GitChange.MODIFIED, "a.txt"))

        assertTimeoutPreemptively(Duration.ofSeconds(30)) {
            val reading =
                CompletableFuture.supplyAsync {
                    slow.fileDiffs(repo, from, GitReviewSide.WorkingTree, files, false, cancellation)
                }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!started.exists()) {
                if (System.nanoTime() > deadline) fail("git did not start filtering")
                Thread.sleep(20)
            }
            cancellation.cancel()
            go.writeText("")

            assertNull(reading.get(10, TimeUnit.SECONDS))
        }
    }
}
