package dev.sluide.git

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * How a file differs between a commit and the working tree (ADR 0044) at the edges: the limits of the size, binary
 * sides, files that are gone or not files, the filters of a path, the diff algorithm; with the real git, also behind a
 * wrapper that fails; skipped without git.
 */
internal class GitLogWorkingTreeTest : GitLogFixture() {
    private val file = GitChangedFile(GitChange.MODIFIED, "a.txt")

    private fun failing(vararg words: String) = GitLog(failingGit(repo.resolve(".git"), *words))

    /** Commits [content] as a.txt and returns the commit. */
    private fun base(content: String = "one\n"): String = commitFiles("Base", "a.txt" to content)

    @Test
    fun `only a full hash is a commit`() {
        base()

        val bad = assertFailsWith<IllegalArgumentException> { log.workingTreeFileDiff(repo, "HEAD", file) }

        assertEquals("not a commit hash: HEAD", bad.message)
    }

    @Test
    fun `a file that is not in the commit, or a path with a line break, has no diff`() {
        val base = base()

        assertNull(log.workingTreeFileDiff(repo, base, GitChangedFile(GitChange.MODIFIED, "no-such.txt")))
        assertNull(log.workingTreeFileDiff(repo, base, GitChangedFile(GitChange.ADDED, "line\nbreak.txt")))
        assertNull(log.workingTreeFileDiff(repo, base, GitChangedFile(GitChange.DELETED, "line\nbreak.txt")))
    }

    @Test
    fun `a git that fails to size or to read the commit's side gives no diff`() {
        val base = base()
        repo.resolve("a.txt").writeText("two\n")

        assertNull(failing("--batch-check").workingTreeFileDiff(repo, base, file), "the sizes")
        assertNull(failing("--filters").workingTreeFileDiff(repo, base, file), "the commit's side")
        assertIs<GitFileDiff.Text>(GitLog(git).workingTreeFileDiff(repo, base, file))
    }

    @Test
    fun `an added, a deleted and an unchanged file are not given to git diff, which would need a temporary folder`() {
        val base = commitFiles("Base", "a.txt" to "one\ntwo\n", "same.txt" to "same\n")
        repo.resolve("new.txt").writeText("x\ny\nz\n")
        repo.resolve("a.txt").deleteExisting()
        // Only its mode changed: the same text on both sides (GitCompareTest has the added and deleted values).
        git("update-index", "--chmod=+x", "same.txt")
        repo.resolve("same.txt").toFile().setExecutable(true)
        // A folder that does not exist: a diff by git would fail.
        log.temporaryFolder = repo.resolve(".git/no-such-folder")

        assertEquals(
            GitFileDiff.Text("", "x\ny\nz\n", listOf(GitHunk(0 until 0, 0 until 3))),
            log.workingTreeFileDiff(repo, base, GitChangedFile(GitChange.ADDED, "new.txt")),
        )
        assertEquals(
            GitFileDiff.Text("one\ntwo\n", "", listOf(GitHunk(0 until 2, 0 until 0))),
            log.workingTreeFileDiff(repo, base, GitChangedFile(GitChange.DELETED, "a.txt")),
        )
        assertEquals(
            GitFileDiff.Text("same\n", "same\n", emptyList()),
            log.workingTreeFileDiff(repo, base, GitChangedFile(GitChange.MODIFIED, "same.txt")),
        )
    }

    @Test
    fun `a binary side on either end is not compared`() {
        val text = commitFiles("Text", "a.txt" to "text\n")
        repo.resolve("a.txt").writeBytes(byteArrayOf(1, 0, 2))
        val binary = commitFiles("Binary")

        // The commit's side is binary, the working tree's is text.
        repo.resolve("a.txt").writeText("text again\n")
        assertEquals(GitFileDiff.Binary, log.workingTreeFileDiff(repo, binary, file))

        // The commit's side is text, the working tree's is binary.
        repo.resolve("a.txt").writeBytes(byteArrayOf(0, 1))
        assertEquals(GitFileDiff.Binary, log.workingTreeFileDiff(repo, text, file))
    }

    @Test
    fun `a file of exactly the largest size is compared, one byte more is too large, in the commit and on disk`() {
        val base = base("12345\n")
        // On disk (read by the JVM).
        repo.resolve("a.txt").writeText("abcde\n")
        assertIs<GitFileDiff.Text>(log.workingTreeFileDiff(repo, base, file, maxSize = 6))
        repo.resolve("a.txt").writeText("abcdef\n")
        assertEquals(GitFileDiff.TooLarge, log.workingTreeFileDiff(repo, base, file, maxSize = 6))

        // In the commit.
        repo.resolve("a.txt").writeText("ab\n")
        assertIs<GitFileDiff.Text>(log.workingTreeFileDiff(repo, base, file, maxSize = 6))
        assertEquals(GitFileDiff.TooLarge, log.workingTreeFileDiff(repo, base, file, maxSize = 5))
    }

    @Test
    fun `where the JVM cannot name a file, git's stored copy and the index are measured against the limit`() {
        val base = base("12345\n")
        log.canNameFile = { false }

        // Not staged: stored by git, then measured.
        repo.resolve("a.txt").writeText("abcde\n")
        assertIs<GitFileDiff.Text>(log.workingTreeFileDiff(repo, base, file, maxSize = 6))
        repo.resolve("a.txt").writeText("abcdef\n")
        assertEquals(GitFileDiff.TooLarge, log.workingTreeFileDiff(repo, base, file, maxSize = 6))

        // Staged with exactly the limit: stored, and then it is too large on disk only.
        repo.resolve("a.txt").writeText("abcde\n")
        git("add", "a.txt")
        repo.resolve("a.txt").writeText("abcde\n")
        assertIs<GitFileDiff.Text>(log.workingTreeFileDiff(repo, base, file, maxSize = 6))

        // A git that cannot store it, size it or read it gives no diff.
        assertNull(failingNamed("hash-object").workingTreeFileDiff(repo, base, file, 6), "storing")
        assertNull(failingNamed("--batch-check", after = 1).workingTreeFileDiff(repo, base, file, 6), "sizing")
        assertNull(failingNamed("blob").workingTreeFileDiff(repo, base, file, 6), "reading")
    }

    private fun failingNamed(
        word: String,
        after: Int = 0,
    ) = GitLog(failingGit(repo.resolve(".git"), word, after = after)).also { it.canNameFile = { false } }

    @Test
    fun `a folder where the file was is no file to compare`() {
        val base = base()
        Files.delete(repo.resolve("a.txt"))
        repo.resolve("a.txt").createDirectories()

        assertNull(log.workingTreeFileDiff(repo, base, file))
    }

    @Test
    fun `the original side is read with the filters of its old path, a too large result is too large`() {
        repo.resolve(".gitattributes").writeText("old.txt text eol=crlf\n")
        val base = commitFiles("Base", "old.txt" to "a\nb\n", "other.txt" to "a\nb\n")
        git("mv", "old.txt", "new.txt")
        repo.resolve("new.txt").writeText("a\nc\n")
        val renamed = GitChangedFile(GitChange.RENAMED, "new.txt", oldPath = "old.txt")

        val diff = log.workingTreeFileDiff(repo, base, renamed)

        // The checkout form of old.txt has CRLF; with the attributes of new.txt it would have none.
        assertEquals(GitFileDiff.Text("a\r\nb\r\n", "a\nc\n", listOf(GitHunk(0 until 2, 0 until 2))), diff)
        // The content as a checkout writes it (6 bytes) is above the limit, the stored blob (4 bytes) is not.
        assertEquals(GitFileDiff.TooLarge, log.workingTreeFileDiff(repo, base, renamed, maxSize = 5))
    }

    @Test
    fun `a submodule in the index in place of a file of the commit is not compared (GitCompareTest has a new one)`() {
        val base = base()
        git("update-index", "--add", "--cacheinfo", "160000,${"1".repeat(40)},a.txt")

        assertEquals(GitFileDiff.Submodule(null, null), log.workingTreeFileDiff(repo, base, file))
    }

    @Test
    fun `a submodule in the commit is not compared (GitCompareTest has the one in the index)`() {
        val base = base()
        git("update-index", "--add", "--cacheinfo", "160000,${"1".repeat(40)},a.txt")
        git("commit", "-q", "-m", "Submodule")
        val committed = git("rev-parse", "HEAD")
        git("rm", "-q", "--cached", "a.txt")
        repo.resolve("a.txt").deleteExisting()
        val gone = GitChangedFile(GitChange.DELETED, "a.txt")

        assertEquals(GitFileDiff.Submodule("1".repeat(40), null), log.workingTreeFileDiff(repo, committed, gone))
        assertIs<GitFileDiff.Text>(log.workingTreeFileDiff(repo, base, gone))
    }

    @Test
    fun `the diff algorithm of the configuration decides the hunks, the last value of several`() {
        val original = "c\na\na\n{\n{\na\n"
        val current = "a\n{\nb\na\nc\n{\n"
        val base = base(original)
        repo.resolve("a.txt").writeText(current)
        val myers = assertIs<GitFileDiff.Text>(log.workingTreeFileDiff(repo, base, file)).hunks

        git("config", "diff.algorithm", "patience")
        val patience = assertIs<GitFileDiff.Text>(log.workingTreeFileDiff(repo, base, file)).hunks
        assertNotEquals(myers, patience)

        git("config", "diff.algorithm", "myers")
        git("config", "--add", "diff.algorithm", "patience")
        assertEquals(patience, assertIs<GitFileDiff.Text>(log.workingTreeFileDiff(repo, base, file)).hunks)
        git("config", "--add", "diff.algorithm", "myers")
        assertEquals(myers, assertIs<GitFileDiff.Text>(log.workingTreeFileDiff(repo, base, file)).hunks)
    }

    @Test
    fun `the temporary folder of the diff is where it is told to be`() {
        val base = base("one\ntwo\n")
        repo.resolve("a.txt").writeText("one\nthree\n")
        log.temporaryFolder = repo.resolve(".git/tmp-diff")

        assertNull(log.workingTreeFileDiff(repo, base, file), "a folder that does not exist")
        log.temporaryFolder!!.createDirectories()
        assertIs<GitFileDiff.Text>(log.workingTreeFileDiff(repo, base, file))
    }

    @Test
    fun `the size of the commit's side is the size of its blob, whatever a filter makes of it`() {
        git("config", "filter.small.smudge", "cut -c1-2")
        repo.resolve(".gitattributes").writeText("a.txt filter=small\n")
        val base = commitFiles("Base", "a.txt" to "abcdefghij\n")
        repo.resolve("a.txt").writeText("x\n")

        // The checkout form has 3 bytes, the blob 11: too large for 5.
        assertEquals(GitFileDiff.TooLarge, log.workingTreeFileDiff(repo, base, file, maxSize = 5))
        assertIs<GitFileDiff.Text>(log.workingTreeFileDiff(repo, base, file, maxSize = 11))
    }

    @Test
    fun `a staged size above the limit decides at once, also when the file on disk is small`() {
        val base = base("12345\n")
        log.canNameFile = { false }
        repo.resolve("a.txt").writeText("abcdefghij\n")
        git("add", "a.txt")
        repo.resolve("a.txt").writeText("ab\n")

        assertEquals(GitFileDiff.TooLarge, log.workingTreeFileDiff(repo, base, file, maxSize = 6))
    }

    @Test
    fun `a deleted file is not compared with the index, where a submodule may stand in its place`() {
        val base = base()
        git("update-index", "--add", "--cacheinfo", "160000,${"1".repeat(40)},a.txt")
        repo.resolve("a.txt").deleteExisting()

        val diff = log.workingTreeFileDiff(repo, base, GitChangedFile(GitChange.DELETED, "a.txt"))

        assertEquals(GitFileDiff.Text("one\n", "", listOf(GitHunk(0 until 1, 0 until 0))), diff)
    }

    @Test
    fun `a FIFO in place of the file is not read`() {
        val base = base()
        repo.resolve("a.txt").deleteExisting()
        val made = runCatching { ProcessBuilder("mkfifo", repo.resolve("a.txt").toString()).start().waitFor() }
        assumeTrue(made.getOrNull() == 0, "mkfifo is not available")
        try {
            // Reading it would block until somebody writes to it.
            var diff: GitFileDiff? = GitFileDiff.Binary
            assertTimeoutPreemptively(Duration.ofSeconds(20)) { diff = log.workingTreeFileDiff(repo, base, file) }

            assertNull(diff)
        } finally {
            // Opened for reading and writing, which does not block, so that a blocked reader ends.
            FileChannel.open(repo.resolve("a.txt"), StandardOpenOption.READ, StandardOpenOption.WRITE).close()
        }
    }

    @Test
    fun `a file that exists on disk but not in the commit has no diff, whatever the change says`() {
        val base = base()
        repo.resolve("new.txt").writeText("x\n")

        assertNull(log.workingTreeFileDiff(repo, base, GitChangedFile(GitChange.MODIFIED, "new.txt")))
    }

    @Test
    fun `a git that cannot read the diff algorithm still compares with its default`() {
        val base = base()
        repo.resolve("a.txt").writeText("two\n")

        val diff = failing("--get-all").workingTreeFileDiff(repo, base, file)

        assertEquals(GitFileDiff.Text("one\n", "two\n", listOf(GitHunk(0 until 1, 0 until 1))), diff)
    }

    @Test
    fun `an untracked file the JVM cannot name is read through git, though the index has no size for it`() {
        val base = base()
        repo.resolve("new.txt").writeText("x\n")
        log.canNameFile = { false }

        val diff = log.workingTreeFileDiff(repo, base, GitChangedFile(GitChange.ADDED, "new.txt"))

        assertEquals(GitFileDiff.Text("", "x\n", listOf(GitHunk(0 until 0, 0 until 1))), diff)
    }
}
