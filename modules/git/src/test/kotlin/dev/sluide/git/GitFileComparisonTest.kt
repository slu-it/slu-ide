package dev.sluide.git

import dev.sluide.git.cli.GitCli
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Comparing two files on disk with git's `--no-index` diff, with the real git; skipped without it. */
class GitFileComparisonTest {
    @TempDir
    lateinit var folder: Path

    @TempDir
    lateinit var temporary: Path

    private val git = GitCli("git", testGitEnvironment())
    private val comparison = GitFileComparison(git)

    @BeforeEach
    fun prepare() {
        assumeTrue(git.available, "git is not installed")
        comparison.temporaryFolder = temporary
    }

    private fun file(
        name: String,
        content: ByteArray,
    ): Path = folder.resolve(name).also { it.writeBytes(content) }

    private fun file(
        name: String,
        content: String,
    ): Path = file(name, content.toByteArray())

    @Test
    fun `changed, added and removed lines give git's hunks and both texts`() {
        val left = file("left.txt", "a\nb\nc\nd\ne\nf\ng\nh\n")
        val right = file("right.txt", "a\nB\nc\nd\nnew\ne\nf\nh\n")

        val diff = assertIs<GitFileDiff.Text>(comparison.compare(left, right))

        assertEquals("a\nb\nc\nd\ne\nf\ng\nh\n", diff.original)
        assertEquals("a\nB\nc\nd\nnew\ne\nf\nh\n", diff.current)
        assertEquals(
            listOf(GitHunk(1 until 2, 1 until 2), GitHunk(4 until 4, 4 until 5), GitHunk(6 until 7, 7 until 7)),
            diff.hunks,
        )
    }

    @Test
    fun `identical files have no hunks and are not given to git`() {
        val missingGit = GitFileComparison(GitCli(folder.resolve("no-such-git").toString(), System.getenv()))
        val left = file("a", "same\n")
        val right = file("b", "same\n")

        val expected = GitFileDiff.Text("same\n", "same\n", emptyList())
        assertEquals(expected, comparison.compare(left, right))
        assertEquals(expected, missingGit.compare(left, right))
    }

    @Test
    fun `an empty file against two lines is one added hunk`() {
        val diff = comparison.compare(file("a", ""), file("b", "x\ny\n"))

        assertEquals(GitFileDiff.Text("", "x\ny\n", listOf(GitHunk(0 until 0, 0 until 2))), diff)
    }

    @ParameterizedTest
    @ValueSource(strings = ["left", "right", "both"])
    fun `a NUL byte on either side is binary`(side: String) {
        val binary = byteArrayOf(1, 0, 2)
        val left = file("left", if (side == "right") "text\n".toByteArray() else binary)
        val right = file("right", if (side == "left") "text\n".toByteArray() else binary)

        assertEquals(GitFileDiff.Binary, comparison.compare(left, right))
    }

    @Test
    fun `a file above the limit is too large`() {
        val small = file("small", "a\n")
        val big = file("big", "abcdef\n")

        assertEquals(GitFileDiff.TooLarge, comparison.compare(small, big, maxSize = 5))
        assertEquals(GitFileDiff.TooLarge, comparison.compare(big, small, maxSize = 5))
        assertIs<GitFileDiff.Text>(comparison.compare(small, big, maxSize = 7))
    }

    @Test
    fun `a missing file or a directory is no comparison`() {
        val file = file("a", "a\n")
        val directory = folder.resolve("dir").createDirectories()

        assertNull(comparison.compare(file, folder.resolve("missing")))
        assertNull(comparison.compare(folder.resolve("missing"), file))
        assertNull(comparison.compare(file, directory))
        assertNull(comparison.compare(directory, file))
    }

    @Test
    fun `without git there is no comparison`() {
        val missingGit = GitFileComparison(GitCli(folder.resolve("no-such-git").toString(), System.getenv()))

        assertNull(missingGit.compare(file("a", "a\n"), file("b", "b\n")))
    }

    // Beyond GitCompareTest: guards that compare reads the files in the JVM instead of diffing the paths in place.
    @Test
    fun `files in a repository are compared as raw bytes, without its attributes or configuration`() {
        val repo = folder.resolve("repo").createDirectories()
        val init =
            ProcessBuilder("git", "init", "-q")
                .directory(repo.toFile())
                .apply {
                    environment().clear()
                    environment().putAll(testGitEnvironment())
                }.start()
        assertEquals(0, init.waitFor())
        repo.resolve(".gitattributes").writeText("* text eol=crlf\n")
        repo.resolve(".git/config").appendText("[core]\n\tautocrlf = true\n")
        val left = repo.resolve("a.txt").also { it.writeBytes("a\r\n".toByteArray()) }
        val right = repo.resolve("b.txt").also { it.writeBytes("a\n".toByteArray()) }

        val diff = assertIs<GitFileDiff.Text>(comparison.compare(left, right))

        assertEquals(listOf(GitHunk(0 until 1, 0 until 1)), diff.hunks)
        assertEquals("a\r\n", diff.original)
    }

    @Test
    fun `the temporary files go to the temporary folder`() {
        val left = file("a", "a\n")
        val right = file("b", "b\n")
        comparison.temporaryFolder = folder.resolve("no-such-folder")

        assertNull(comparison.compare(left, right))
    }
}
