package dev.sluide.git

import dev.sluide.git.cli.GitCli
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

    @Test
    fun `a submodule on the original side names only that side`() {
        assertEquals(GitFileDiff.Submodule("a".repeat(40), null), diff(submodule("a".repeat(40)), blob("x\n")))
    }

    @Test
    fun `a submodule on the current side names only that side`() {
        assertEquals(GitFileDiff.Submodule(null, "b".repeat(40)), diff(blob("x\n"), submodule("b".repeat(40))))
    }

    @Test
    fun `submodules on both sides name both`() {
        assertEquals(
            GitFileDiff.Submodule("a".repeat(40), "b".repeat(40)),
            diff(submodule("a".repeat(40)), submodule("b".repeat(40))),
        )
    }

    @Test
    fun `an original blob that cannot be read gives null`() {
        val original = blob("one\n")
        val current = blob("two\n")
        assertNull(diff(original, current) { if (it === original) null else contents[it.name] })
    }

    @Test
    fun `a current blob that cannot be read gives null`() {
        val original = blob("one\n")
        val current = blob("two\n")
        assertNull(diff(original, current) { if (it === current) null else contents[it.name] })
    }

    @Test
    fun `a binary original side gives Binary`() {
        assertEquals(GitFileDiff.Binary, diff(blob(byteArrayOf(65, 0, 66)), blob("text\n")))
    }

    @Test
    fun `a binary current side gives Binary`() {
        assertEquals(GitFileDiff.Binary, diff(blob("text\n"), blob(byteArrayOf(65, 0, 66))))
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
    fun `wholeFile is one hunk for added lines and one for deleted lines, none for no lines`() {
        assertEquals(listOf(GitHunk(0 until 0, 0 until 3)), wholeFile(3, added = true))
        assertEquals(listOf(GitHunk(0 until 3, 0 until 0)), wholeFile(3, added = false))
        assertEquals(emptyList(), wholeFile(0, added = true))
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
