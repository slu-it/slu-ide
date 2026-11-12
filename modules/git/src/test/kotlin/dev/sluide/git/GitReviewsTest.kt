package dev.sluide.git

import dev.sluide.git.cli.GitCli
import dev.sluide.git.cli.inputChunks
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.IOException
import java.nio.file.InvalidPathException
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The files and diffs the review window reads (ADR 0168), with the real git; skipped without it. */
internal class GitReviewsTest : GitLogFixture() {
    private val reviews = GitReviews(git)

    private val commitSide: (String) -> GitReviewSide = { GitReviewSide.Commit(it) }

    @Test
    fun `a range of commits has its renames with and without changes, added and deleted files`() {
        val from =
            commitFiles(
                "First",
                "same.txt" to "same\n",
                "old/name.txt" to lines,
                "gone.txt" to "a\nb\n",
            )
        commitFiles("Second", "same.txt" to null, "moved.txt" to "same\n")
        val to =
            commitFiles(
                "Third",
                "old/name.txt" to null,
                "new/name.txt" to lines.replace("line 5\n", "line five\n"),
                "gone.txt" to null,
                "added.txt" to "x\ny\nz",
            )
        val side = commitSide(to)

        val files = reviews.changedFiles(repo, from, side)

        assertEquals(
            listOf(
                GitChangedFile(GitChange.ADDED, "added.txt"),
                GitChangedFile(GitChange.DELETED, "gone.txt"),
                GitChangedFile(GitChange.RENAMED, "moved.txt", "same.txt"),
                GitChangedFile(GitChange.RENAMED, "new/name.txt", "old/name.txt"),
            ),
            files?.sortedBy { it.path },
        )
        val diffs = assertNotNull(reviews.fileDiffs(repo, from, side, files.orEmpty(), ignoreWhitespace = false))
        assertEquals(GitFileDiff.Text("", "x\ny\nz", listOf(GitHunk(0 until 0, 0 until 3))), diffs["added.txt"])
        assertEquals(GitFileDiff.Text("a\nb\n", "", listOf(GitHunk(0 until 2, 0 until 0))), diffs["gone.txt"])
        assertEquals(GitFileDiff.Text("same\n", "same\n", emptyList()), diffs["moved.txt"])
        val renamed = assertIs<GitFileDiff.Text>(diffs["new/name.txt"])
        assertEquals(lines, renamed.original)
        assertEquals(listOf(GitHunk(4 until 5, 4 until 5)), renamed.hunks)
        assertEquals(4, diffs.size)
    }

    @Test
    fun `a range from the empty tree adds every file of the commit`() {
        val root = commitFiles("First", "a.txt" to "a\n", "dir/b.txt" to "")

        val files = reviews.changedFiles(repo, null, commitSide(root))
        val diffs = reviews.fileDiffs(repo, null, commitSide(root), files.orEmpty(), ignoreWhitespace = false)

        assertEquals(
            listOf(GitChangedFile(GitChange.ADDED, "a.txt"), GitChangedFile(GitChange.ADDED, "dir/b.txt")),
            files,
        )
        assertEquals(
            mapOf(
                "a.txt" to GitFileDiff.Text("", "a\n", listOf(GitHunk(0 until 0, 0 until 1))),
                "dir/b.txt" to GitFileDiff.Text("", "", emptyList()),
            ),
            diffs,
        )
    }

    @Test
    fun `binary and too large files are not compared, the others in the same call are`() {
        val from = commitFiles("First", "big.txt" to "small\n", "image.bin" to "text\n", "a.txt" to "a\n")
        repo.resolve("image.bin").writeBytes(byteArrayOf(1, 0, 2))
        val to = commitFiles("Second", "big.txt" to "now larger than ten bytes\n", "a.txt" to "b\n")
        reviews.maxSize = 10

        val files = reviews.changedFiles(repo, from, commitSide(to)).orEmpty()
        val diffs = reviews.fileDiffs(repo, from, commitSide(to), files, ignoreWhitespace = false)

        assertEquals(
            mapOf(
                "a.txt" to GitFileDiff.Text("a\n", "b\n", listOf(GitHunk(0 until 1, 0 until 1))),
                "big.txt" to GitFileDiff.TooLarge,
                "image.bin" to GitFileDiff.Binary,
            ),
            diffs,
        )
    }

    @Test
    fun `a submodule gives the commits it points at before and after`() {
        val old = "1".repeat(40)
        val new = "2".repeat(40)
        git("update-index", "--add", "--cacheinfo", "160000,$old,lib")
        val from = commit("Add", 1000)
        git("update-index", "--cacheinfo", "160000,$new,lib")
        val to = commit("Move", 2000)
        val lib = GitChangedFile(GitChange.MODIFIED, "lib")

        assertEquals(listOf(lib), reviews.changedFiles(repo, from, commitSide(to)))
        assertEquals(
            mapOf("lib" to GitFileDiff.Submodule(old, new)),
            reviews.fileDiffs(repo, from, commitSide(to), listOf(lib), ignoreWhitespace = false),
        )
    }

    @Test
    fun `hiding whitespace drops a whitespace-only hunk and keeps the real change, in commits and working tree`() {
        val original = "a b\nkeep\nc\n"
        val from = commitFiles("First", "a.txt" to original, "spaces.txt" to "x y\n")
        val to = commitFiles("Second", "a.txt" to "a  b\nkeep\nchanged\n", "spaces.txt" to "x\ty \n")
        val files =
            listOf(GitChangedFile(GitChange.MODIFIED, "a.txt"), GitChangedFile(GitChange.MODIFIED, "spaces.txt"))
        git("reset", "-q", "--soft", from)

        for (side in listOf(commitSide(to), GitReviewSide.WorkingTree)) {
            val all = assertNotNull(reviews.fileDiffs(repo, from, side, files, ignoreWhitespace = false), "$side")
            val hidden = assertNotNull(reviews.fileDiffs(repo, from, side, files, ignoreWhitespace = true), "$side")

            assertEquals(
                listOf(GitHunk(0 until 1, 0 until 1), GitHunk(2 until 3, 2 until 3)),
                assertIs<GitFileDiff.Text>(all["a.txt"]).hunks,
                "$side",
            )
            assertEquals(listOf(GitHunk(2 until 3, 2 until 3)), assertIs<GitFileDiff.Text>(hidden["a.txt"]).hunks)
            assertEquals(1, assertIs<GitFileDiff.Text>(all["spaces.txt"]).hunks.size, "$side")
            assertEquals(GitFileDiff.Text("x y\n", "x\ty \n", emptyList()), hidden["spaces.txt"], "$side")
        }
    }

    @Test
    fun `the working tree has the tracked changes, staged or not, and the untracked files as added`() {
        val from = commitFiles("First", "a.txt" to "a\n", "b.txt" to "b\n", "c.txt" to "c\n", ".gitignore" to "*.log\n")
        repo.resolve("a.txt").writeText("changed\n")
        repo.resolve("b.txt").writeText("staged\n")
        git("add", "b.txt")
        git("rm", "-q", "c.txt")
        repo.resolve("dir").createDirectories()
        repo.resolve("dir/new.txt").writeText("new\n")
        repo.resolve("ignored.log").writeText("ignored\n")

        val files = reviews.changedFiles(repo, from, GitReviewSide.WorkingTree).orEmpty()
        val diffs = reviews.fileDiffs(repo, from, GitReviewSide.WorkingTree, files, ignoreWhitespace = false)

        assertEquals(
            listOf(
                GitChangedFile(GitChange.MODIFIED, "a.txt"),
                GitChangedFile(GitChange.MODIFIED, "b.txt"),
                GitChangedFile(GitChange.DELETED, "c.txt"),
                GitChangedFile(GitChange.ADDED, "dir/new.txt"),
            ),
            files,
        )
        assertEquals(GitFileDiff.Text("", "new\n", listOf(GitHunk(0 until 0, 0 until 1))), diffs?.get("dir/new.txt"))
        assertEquals(GitFileDiff.Text("b\n", "staged\n", listOf(GitHunk(0 until 1, 0 until 1))), diffs?.get("b.txt"))
        assertEquals(GitFileDiff.Text("c\n", "", listOf(GitHunk(0 until 1, 0 until 0))), diffs?.get("c.txt"))
    }

    @Test
    fun `the working tree from the empty tree adds the tracked and the untracked files`() {
        commitFiles("First", "a.txt" to "a\n")
        repo.resolve("new.txt").writeText("new\n")

        val files = reviews.changedFiles(repo, null, GitReviewSide.WorkingTree).orEmpty()

        assertEquals(
            listOf(GitChangedFile(GitChange.ADDED, "a.txt"), GitChangedFile(GitChange.ADDED, "new.txt")),
            files,
        )
        assertEquals(
            GitFileDiff.Text("", "a\n", listOf(GitHunk(0 until 0, 0 until 1))),
            reviews.fileDiffs(repo, null, GitReviewSide.WorkingTree, files, ignoreWhitespace = false)?.get("a.txt"),
        )
    }

    @Test
    fun `a file removed from the index but still on disk is listed once, as modified`() {
        val from = commitFiles("First", "a.txt" to "a\n")
        git("rm", "-q", "--cached", "a.txt")
        repo.resolve("a.txt").writeText("b\n")

        val files = reviews.changedFiles(repo, from, GitReviewSide.WorkingTree).orEmpty()

        assertEquals(listOf(GitChangedFile(GitChange.MODIFIED, "a.txt")), files)
        assertEquals(
            GitFileDiff.Text("a\n", "b\n", listOf(GitHunk(0 until 1, 0 until 1))),
            reviews.fileDiffs(repo, from, GitReviewSide.WorkingTree, files, ignoreWhitespace = false)?.get("a.txt"),
        )
    }

    @Test
    fun `more files than one standard input holds are read in several runs`() {
        val count = 400
        val name = "a-rather-long-folder-name-to-fill-the-standard-input/file-with-a-long-name"
        val from = commitFiles("First", *Array(count) { "$name-$it.txt" to "old $it\n" })
        val to = commitFiles("Second", *Array(count) { "$name-$it.txt" to "new $it\n" })
        val files = reviews.changedFiles(repo, from, commitSide(to)).orEmpty()

        val diffs = reviews.fileDiffs(repo, from, commitSide(to), files, ignoreWhitespace = false)

        assertEquals(count, diffs?.size)
        assertEquals(
            GitFileDiff.Text("old 7\n", "new 7\n", listOf(GitHunk(0 until 1, 0 until 1))),
            diffs?.get("$name-7.txt"),
        )
    }

    @Test
    fun `blobs are read in runs of a limited size, with the same result`() {
        val from = commitFiles("First", "a.txt" to "a\n", "b.txt" to "b\n", "c.txt" to "c\n")
        val to = commitFiles("Second", "a.txt" to "aa\n", "b.txt" to "bb\n", "c.txt" to "cc\n")
        val runs = repo.resolve(".git/batch-runs")
        // Runs the real git, and adds a line to the file for every `git cat-file --batch`.
        val executable =
            repo.resolve(".git/counting-git").apply {
                writeText(
                    "#!/bin/sh\ncase \" $* \" in *' cat-file --batch '*) echo run >> '$runs';; esac\nexec git \"$@\"\n",
                )
                toFile().setExecutable(true)
            }
        val counting = GitReviews(GitCli(executable.toString(), testGitEnvironment()))
        val files = counting.changedFiles(repo, from, commitSide(to)).orEmpty()

        fun runsOf(diffs: () -> Map<String, GitFileDiff>?): Pair<Map<String, GitFileDiff>?, Int> {
            runs.deleteIfExists()
            val result = diffs()
            return result to if (runs.exists()) runs.readLines().size else 0
        }

        val (whole, wholeRuns) =
            runsOf {
                counting.fileDiffs(
                    repo,
                    from,
                    commitSide(to),
                    files,
                    ignoreWhitespace = false,
                )
            }
        counting.maxBatchOutput = 3
        val (split, splitRuns) =
            runsOf {
                counting.fileDiffs(
                    repo,
                    from,
                    commitSide(to),
                    files,
                    ignoreWhitespace = false,
                )
            }

        assertEquals(3, whole?.size)
        assertEquals(whole, split)
        assertEquals(1, wholeRuns)
        // Six blobs of two or three bytes: at most one per run.
        assertEquals(6, splitRuns)
    }

    @Test
    fun `runs end before their weight passes the limit, a heavier line alone`() {
        val weights = mapOf("a" to 10L, "b" to 10L, "c" to 25L, "d" to 5L, "e" to 1L)

        assertEquals(
            listOf(listOf("a", "b"), listOf("c"), listOf("d", "e")),
            inputChunks(weights.keys.toList(), weights::getValue, maxWeight = 20),
        )
        assertEquals(listOf(weights.keys.toList()), inputChunks(weights.keys.toList()))
    }

    @Test
    fun `a path with a carriage return is left out, never read as the path without it`() {
        val names = listOf("a", "a\r")
        try {
            names.forEach { repo.resolve(it).writeText("$it old\n") }
        } catch (e: InvalidPathException) {
            assumeTrue(false, "the file system refuses a carriage return in a name: $e")
        } catch (e: IOException) {
            assumeTrue(false, "the file system refuses a carriage return in a name: $e")
        }
        git("add", "-A")
        val from = commit("First", 1000)
        names.forEach { repo.resolve(it).writeText("$it new\n") }
        git("add", "-A")
        val to = commit("Second", 2000)
        names.forEach { repo.resolve(it).writeText("$it newer\n") }
        val files = names.map { GitChangedFile(GitChange.MODIFIED, it) }

        for (side in listOf(commitSide(to), GitReviewSide.WorkingTree)) {
            val diffs = reviews.fileDiffs(repo, from, side, files, ignoreWhitespace = false)

            assertEquals(setOf("a"), diffs?.keys, "$side")
            assertEquals("a old\n", assertIs<GitFileDiff.Text>(diffs?.get("a")).original, "$side")
        }
        assertNull(log.fileDiff(repo, to, from, files.last()))
        assertNull(log.workingTreeFileDiff(repo, to, files.last()))
    }

    @Test
    fun `a file whose blob is missing or whose path has a line break is left out, the others are read`() {
        val from = commitFiles("First", "a.txt" to "a\n")
        val to = commitFiles("Second", "a.txt" to "b\n")
        val files =
            listOf(
                GitChangedFile(GitChange.MODIFIED, "a.txt"),
                GitChangedFile(GitChange.MODIFIED, "no-such-file.txt"),
                GitChangedFile(GitChange.MODIFIED, "a.txt\nb.txt"),
            )

        val diffs = reviews.fileDiffs(repo, from, commitSide(to), files, ignoreWhitespace = false)

        assertEquals(setOf("a.txt"), diffs?.keys)
    }

    @Test
    fun `tells whether there are uncommitted changes, untracked files included and ignored ones not`() {
        commitFiles("First", "a.txt" to "a\n", ".gitignore" to "*.log\n")
        repo.resolve("ignored.log").writeText("ignored\n")
        assertEquals(false, reviews.hasUncommittedChanges(repo))

        repo.resolve("new.txt").writeText("new\n")
        assertEquals(true, reviews.hasUncommittedChanges(repo))

        git("add", "new.txt")
        assertEquals(true, reviews.hasUncommittedChanges(repo))
    }

    @Test
    fun `a cancelled read runs no git and answers nothing`() {
        val from = commitFiles("First", "a.txt" to "a\n")
        val to = commitFiles("Second", "a.txt" to "b\n")
        val files = listOf(GitChangedFile(GitChange.MODIFIED, "a.txt"))
        val cancelled = GitCancellation().apply { cancel() }

        assertNull(reviews.mergeBase(repo, from, to, cancelled))
        assertNull(reviews.commits(repo, from, to, cancellation = cancelled))
        for (side in listOf(commitSide(to), GitReviewSide.WorkingTree)) {
            assertNull(reviews.changedFiles(repo, from, side, cancelled), "$side")
            assertNull(reviews.fileDiffs(repo, from, side, files, ignoreWhitespace = false, cancelled), "$side")
        }
        // Not cancelled, the same calls answer.
        assertNotNull(reviews.fileDiffs(repo, from, commitSide(to), files, ignoreWhitespace = false))
        assertNotNull(reviews.fileDiffs(repo, from, GitReviewSide.WorkingTree, files, ignoreWhitespace = false))
    }

    @ParameterizedTest
    @CsvSource("cat-file --batch, commit", "diff --no-color, commit", "diff --no-index, working tree")
    fun `cancelling the diffs while git runs stops git and gives nothing`(
        command: String,
        side: String,
    ) {
        val from = commitFiles("First", "a.txt" to "a\n")
        val to = commitFiles("Second", "a.txt" to "b\n")
        val pidFile = repo.resolve(".git/slow.pid")
        // Runs the real git, but for the command under test writes its process id and sleeps instead.
        val executable =
            repo.resolve(".git/slow-git").apply {
                writeText(
                    "#!/bin/sh\ncase \" $* \" in *' $command '*) echo $$ > '$pidFile'; exec sleep 30;; esac\n" +
                        "exec git \"$@\"\n",
                )
                toFile().setExecutable(true)
            }
        val slow = GitReviews(GitCli(executable.toString(), testGitEnvironment()))
        val reviewed = if (side == "commit") commitSide(to) else GitReviewSide.WorkingTree
        val files = listOf(GitChangedFile(GitChange.MODIFIED, "a.txt"))
        val cancellation = GitCancellation()
        var result: Map<String, GitFileDiff>? = emptyMap()
        val caller =
            Thread.ofPlatform().start {
                result = slow.fileDiffs(repo, from, reviewed, files, ignoreWhitespace = false, cancellation)
            }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!pidFile.exists() || pidFile.readText().isBlank()) {
            if (System.nanoTime() > deadline) fail("git did not start")
            Thread.sleep(20)
        }
        val process = ProcessHandle.of(pidFile.readText().trim().toLong())
        assertTrue(process.isPresent, "git runs")

        val cancelledAt = System.nanoTime()
        cancellation.cancel()
        caller.join(TimeUnit.SECONDS.toMillis(10))

        assertFalse(caller.isAlive)
        assertTrue(System.nanoTime() - cancelledAt < TimeUnit.SECONDS.toNanos(5), "not waiting for git's end")
        assertNull(result)
        // Killed, not left to sleep its 30 s; polled, as waiting on onExit() may stall with few cores.
        val killed = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (process.get().isAlive && System.nanoTime() < killed) Thread.sleep(20)
        assertFalse(process.get().isAlive, "git is stopped")
    }

    @Test
    fun `only full hashes are taken as commits`() {
        val hash = commitFiles("First", "a.txt" to "a\n")

        for (revision in listOf("HEAD", hash.take(12), "-p")) {
            assertTrue(runCatching { reviews.changedFiles(repo, revision, commitSide(hash)) }.isFailure, revision)
            assertTrue(runCatching { reviews.changedFiles(repo, null, commitSide(revision)) }.isFailure, revision)
            assertTrue(runCatching { reviews.commits(repo, null, revision) }.isFailure, revision)
        }
        assertFalse(runCatching { reviews.commits(repo, null, hash) }.isFailure)
    }
}
