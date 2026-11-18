package dev.sluide.git

import dev.sluide.git.cli.GitCli
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [blobDiff], [wholeFile] and [GitObject.of] with the real git in a temporary repository; skipped without it. */
internal class GitBlobDiffsTest : GitCommitsFixture() {
    private val contents = mutableMapOf<String, ByteArray>()

    private fun blob(text: String): GitObject = blob(text.toByteArray())

    private fun blob(bytes: ByteArray): GitObject {
        val name = git("hash-object", "-w", "--stdin", input = String(bytes, Charsets.ISO_8859_1))
        contents[name] = bytes
        return GitObject(name, bytes.size.toLong(), submodule = false)
    }

    private fun submodule(name: String) = GitObject(name, 0, submodule = true)

    private fun diff(
        original: GitObject?,
        current: GitObject?,
        cli: GitCli = git,
        read: (GitObject) -> ByteArray? = { contents[it.name] },
    ) = cli.blobDiff(repo, original, current, maxSize = 1000, read = read)

    @ParameterizedTest
    @CsvSource("true,false", "false,true", "true,true")
    fun `a submodule names the sides it is on`(
        onOriginal: Boolean,
        onCurrent: Boolean,
    ) {
        val original = "a".repeat(40)
        val current = "b".repeat(40)

        val result =
            diff(
                if (onOriginal) submodule(original) else blob("x\n"),
                if (onCurrent) submodule(current) else blob("y\n"),
            )

        assertEquals(GitFileDiff.Submodule(original.takeIf { onOriginal }, current.takeIf { onCurrent }), result)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a blob that cannot be read gives null, on either side`(original: Boolean) {
        val before = blob("one\n")
        val after = blob("two\n")
        val unreadable = if (original) before else after

        assertNull(diff(before, after) { if (it === unreadable) null else contents[it.name] })
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `a NUL byte on either side gives Binary`(original: Boolean) {
        val binary = byteArrayOf(65, 0, 66)

        val result =
            diff(
                blob(if (original) binary else "text\n".toByteArray()),
                blob(if (original) "text\n".toByteArray() else binary),
            )

        assertEquals(GitFileDiff.Binary, result)
    }

    @Test
    fun `an added file is one hunk of all its lines`() {
        assertEquals(
            GitFileDiff.Text("", "a\nb\n", listOf(GitHunk(0 until 0, 0 until 2))),
            diff(null, blob("a\nb\n")),
        )
    }

    @Test
    fun `a deleted file is one hunk of all its lines`() {
        assertEquals(
            GitFileDiff.Text("a\nb\n", "", listOf(GitHunk(0 until 2, 0 until 0))),
            diff(blob("a\nb\n"), null),
        )
    }

    @Test
    fun `the same blob on both sides has no hunks and does not call git diff`() {
        val same = blob("a\nb\n")
        // A git that fails every `diff`: a mutant that diffed the same blob would give null.
        val result = diff(same, same, failingGit(folder, "diff"))
        assertEquals(GitFileDiff.Text("a\nb\n", "a\nb\n", emptyList()), result)
    }

    @Test
    fun `two different blobs have the hunks of git diff`() {
        assertEquals(
            GitFileDiff.Text("a\nb\n", "a\nc\n", listOf(GitHunk(1 until 2, 1 until 2))),
            diff(blob("a\nb\n"), blob("a\nc\n")),
        )
    }

    @Test
    fun `a failing git diff gives null`() {
        assertNull(diff(blob("a\n"), blob("b\n"), failingGit(folder, "diff")))
    }

    @Test
    fun `wholeFile is none for no lines`() {
        assertEquals(emptyList(), wholeFile(0, added = true))
        assertEquals(emptyList(), wholeFile(0, added = false))
    }

    @Test
    fun `GitObject reads blob and submodule lines and nothing else`() {
        val name = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(42L, GitObject.of("$name blob 42")?.size)
        assertEquals(name, GitObject.of("$name blob 42")?.name)
        assertEquals(false, GitObject.of("$name blob 42")?.submodule)
        assertEquals(true, GitObject.of("$name submodule")?.submodule)
        assertEquals(name, GitObject.of("$name submodule")?.name)
        assertNull(GitObject.of("$name blob 99999999999999999999"))
        assertNull(GitObject.of("$name tree 4"))
        assertNull(GitObject.of("$name missing"))
    }
}
