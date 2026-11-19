package dev.sluide.git.cli

import dev.sluide.git.FAKE_GIT_START
import dev.sluide.git.GitCancellation
import dev.sluide.git.failingGit
import dev.sluide.git.recordedCalls
import dev.sluide.git.recordingGit
import dev.sluide.git.testGitEnvironment
import dev.sluide.git.unavailableGit
import dev.sluide.git.vanishedGit
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The object readers of [GitCli] (`GitCliObjects.kt`) with the real git: what they answer for a repository, and that a
 * git that fails, or is not available, gives no answer even where the real one would.
 */
class GitCliObjectsTest {
    @TempDir
    lateinit var folder: Path

    private val git = GitCli("git", testGitEnvironment())
    private lateinit var repo: TestRepository
    private val root get() = repo.root
    private lateinit var first: String
    private lateinit var second: String

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo = TestRepository(folder.resolve("repo"))
        repo.write("a.txt", "a")
        repo.write("dir/b.txt", "b")
        repo.git("add", ".")
        repo.git("commit", "-q", "-m", "first")
        first = repo.head()
        second = repo.commit("second", "a.txt")
    }

    private val unavailable get() = unavailableGit(folder)

    private fun failing(word: String) = failingGit(folder, word)

    /** A stand-in for git that runs [body] for anything but `--version`. */
    private fun fakeGit(
        body: String,
        pipeGrace: Duration = 300.milliseconds,
    ): GitCli {
        val script =
            folder.resolve("fake-git-${System.nanoTime()}").apply {
                writeText(FAKE_GIT_START + body + "\n")
                toFile().setExecutable(true)
            }
        return GitCli(script.toString(), testGitEnvironment(), 2.seconds, pipeGrace).also {
            assumeTrue(it.available, "no /bin/sh to stand in for git")
        }
    }

    /** A call of [word] that ends with [exit] after it wrote [output] (a shell word). */
    private fun answering(
        word: String,
        output: String,
        exit: Int = 0,
        hold: Boolean = false,
    ) = fakeGit(
        "case \" \$* \" in *' $word '*) $output; ${if (hold) "(sleep 3 &); sleep 0.3;" else ""} exit $exit;; esac",
    )

    @Test
    fun `commitOf is the commit a hash prefix of 4 to 64 hexadecimal characters names`() {
        assertEquals(second, git.commitOf(root, second.take(4)))
        assertEquals(second, git.commitOf(root, second))
        assertEquals(second, git.commitOf(root, second.take(12).uppercase()))
        assertNull(git.commitOf(root, second.take(3)), "git would resolve it: the prefix is too short")
        assertNull(git.commitOf(root, "HEAD"), "git would resolve it: it is no hash")
        assertNull(git.commitOf(root, second + "0".repeat(25)), "65 characters")
        assertNull(git.commitOf(root, "ffff"), "no such commit")
        assertNull(unavailable.commitOf(root, second))
        assertNull(failing("rev-parse").commitOf(root, second))
        assertNull(answering("rev-parse", "true").commitOf(root, second), "nothing printed")
        assertEquals(second, answering("rev-parse", "echo ' $second '").commitOf(root, "abcd"))
    }

    @Test
    fun `treePaths are the records of the tree, folders before what they hold`() {
        val tree = git.treePaths(root, second)

        assertEquals(
            "100644 blob ${repo.git("rev-parse", "$second:a.txt")}\ta.txt\u0000" +
                "040000 tree ${repo.git("rev-parse", "$second:dir")}\tdir\u0000" +
                "100644 blob ${repo.git("rev-parse", "$second:dir/b.txt")}\tdir/b.txt\u0000",
            tree,
        )
        assertNull(unavailable.treePaths(root, second))
        assertNull(failing("ls-tree").treePaths(root, second))
    }

    @Test
    fun `show is git's output for the commit alone`() {
        val shown = git.show(root, second)

        assertNotNull(shown)
        assertTrue(shown.startsWith("$second\u001f$first\u001fTest\u001ftest@example.com\u001f"), shown)
        assertTrue(shown.endsWith("\u001fsecond\n\n"), shown)
        assertNull(git.show(root, "f".repeat(40)))
        assertNull(unavailable.show(root, second))
        assertNull(failing("show").show(root, second))
    }

    @Test
    fun `changedFiles are the name-status records against the parent, or against nothing for a root commit`() {
        assertEquals("M\u0000a.txt\u0000", git.changedFiles(root, second, first))
        assertEquals("A\u0000a.txt\u0000A\u0000dir/b.txt\u0000", git.changedFiles(root, first, null))
        assertNull(git.changedFiles(root, "f".repeat(40), first))
        assertNull(unavailable.changedFiles(root, second, first))
        assertNull(failing("diff-tree").changedFiles(root, second, first))
    }

    @Test
    fun `objectInfo is git's batch-check output for the names, which must be one line each`() {
        val names = listOf("$second:a.txt", "$second:missing", "$second:dir")
        val expected = repo.git("cat-file", "--batch-check", input = names.joinToString("") { "$it\n" })

        assertEquals(expected + "\n", git.objectInfo(root, names))
        assertEquals("", git.objectInfo(root, emptyList()))
        assertNull(git.objectInfo(root, listOf("HEAD:a\nb")), "git would read two names")
        assertNull(git.objectInfo(root, listOf("HEAD:a\r")), "git would drop the carriage return")
        assertNull(git.objectInfo(root, listOf("HEAD:a", "HEAD:\rb")))
        assertNull(unavailable.objectInfo(root, names))
        assertNull(failing("cat-file").objectInfo(root, names))
    }

    @Test
    fun `resolveRevisions gives two lines per revision, the commit it names and the revision itself`() {
        val revisions = listOf("HEAD", "no-such")
        val input = "HEAD^{commit}\nHEAD\nno-such^{commit}\nno-such\n"
        val expected = repo.git("cat-file", "--batch-check", input = input)

        assertEquals(expected + "\n", git.resolveRevisions(root, revisions))
        assertNull(git.resolveRevisions(root, listOf("HEAD\n")), "a line break")
        assertNull(git.resolveRevisions(root, listOf("a", "b\nc")))
        assertNull(unavailable.resolveRevisions(root, revisions))
        assertNull(failing("cat-file").resolveRevisions(root, revisions))
    }

    @Test
    fun `resolveRevisions takes input up to the limit of git's standard input, not more`() {
        // Each revision r adds "r^{commit}\n" and "r\n": 2 * length + 11 bytes.
        fun revisions(total: Int) = listOf("x".repeat(total / 2), "y".repeat(total - total / 2))
        // Two names of 3834 and 3835 characters: 2 * 7669 + 22 = 15360 bytes, exactly GitCli.MAX_INPUT.
        val exact = revisions(7669)
        val over = revisions(7670)

        assertEquals(GitCli.MAX_INPUT, exact.sumOf { 2 * it.length + 11 })
        assertNotNull(git.resolveRevisions(root, exact))
        assertEquals(GitCli.MAX_INPUT + 2, over.sumOf { 2 * it.length + 11 })
        assertNull(git.resolveRevisions(root, over))
    }

    @Test
    fun `branchesContaining lists the branches with the commit, one refname and symref each`() {
        repo.git("branch", "old", first)

        assertEquals("refs/heads/main\u001f\n", git.branchesContaining(root, second))
        assertEquals("refs/heads/main\u001f\nrefs/heads/old\u001f\n", git.branchesContaining(root, first))
        assertNull(unavailable.branchesContaining(root, second))
        assertNull(failing("for-each-ref").branchesContaining(root, second))
    }

    @Test
    fun `blob is the raw content, objectSize its size`() {
        val blob = repo.git("rev-parse", "$second:a.txt")

        assertContentEquals("second\n".toByteArray(), git.blob(root, blob))
        assertEquals(7L, git.objectSize(root, blob))
        assertNull(git.blob(root, "f".repeat(40)))
        assertNull(git.objectSize(root, "f".repeat(40)), "missing")
        assertNull(unavailable.blob(root, blob))
        assertNull(failing("cat-file").blob(root, blob))
        assertNull(unavailable.objectSize(root, blob))
    }

    @Test
    fun `diffBlobs without context, with or without the whitespace`() {
        repo.write("w.txt", "a b")
        val spaced = repo.git("hash-object", "-w", "w.txt")
        repo.write("w.txt", "a  b")
        val wider = repo.git("hash-object", "-w", "w.txt")

        assertTrue("@@ -1 +1 @@" in assertNotNull(git.diffBlobs(root, spaced, wider)))
        assertEquals("", git.diffBlobs(root, spaced, wider, ignoreWhitespace = true))
        assertNull(unavailable.diffBlobs(root, spaced, wider))
        assertNull(failing("diff").diffBlobs(root, spaced, wider))
    }

    @Test
    fun `workingTreeChanges are the name-status records of the tracked files against a commit`() {
        repo.write("a.txt", "changed")

        assertEquals("M\u0000a.txt\u0000", git.workingTreeChanges(root, second))
        assertEquals("M\u0000a.txt\u0000", git.workingTreeChanges(root, first).also { assertNotNull(it) })
        assertNull(git.workingTreeChanges(root, second, GitCancellation().apply { cancel() }))
        assertNull(unavailable.workingTreeChanges(root, second))
        assertNull(failing("diff").workingTreeChanges(root, second))
    }

    @Test
    fun `emptyTree is the name of the empty tree`() {
        assertEquals("4b825dc642cb6eb9a060e54bf8d69288fbee4904", git.emptyTree(root))
        assertEquals("6ef19b41225c5369f1c104d45d8d85efa9b057b53b14b4b9b939dd74decc5321", git.emptyTree(sha256().root))
        assertNull(unavailable.emptyTree(root))
        assertNull(failing("hash-object").emptyTree(root))
    }

    private fun sha256() = TestRepository(folder.resolve("sha256"), objectFormat = "sha256")

    @Test
    fun `storeFiles gives the object names in order, for paths with spaces and non-ASCII characters`() {
        repo.write("one two.txt", "1")
        // Made by git, as the JVM may not name a file with it in a POSIX locale; then untracked.
        val blob = repo.git("hash-object", "-w", "--stdin", input = "2\n")
        repo.git("update-index", "--add", "--index-info", input = "100644 $blob\tk\u00f6ln.txt\n")
        repo.git("checkout", "-q", "--", ".")
        repo.git("update-index", "--force-remove", "--stdin", input = "k\u00f6ln.txt\n")
        val paths = listOf("one two.txt", "k\u00f6ln.txt")
        val expected = paths.map { repo.git("hash-object", "--stdin-paths", input = "$it\n") }

        assertEquals(expected, git.storeFiles(root, paths))
        assertEquals("blob", repo.git("cat-file", "-t", expected[0]))
        assertEquals(emptyList(), git.storeFiles(root, emptyList()))
        assertEquals(emptyList(), failing("hash-object").storeFiles(root, emptyList()), "nothing to store, no call")
        assertNull(git.storeFiles(root, listOf("one two.txt\n")), "git would read two paths")
        assertNull(git.storeFiles(root, listOf("missing.txt")))
        assertNull(unavailable.storeFiles(root, listOf("a.txt")))
        assertNull(unavailable.storeFiles(root, emptyList()))
        assertNull(failing("hash-object").storeFiles(root, listOf("a.txt")))
    }

    @Test
    fun `storeFiles has no answer for fewer names than paths`() {
        assertNull(answering("hash-object", "echo abc").storeFiles(root, listOf("a.txt", "b.txt")))
        val blankLine = answering("hash-object", "printf 'abc\\n\\ndef\\n'")
        assertEquals(listOf("abc", "def"), blankLine.storeFiles(root, listOf("a", "b")))
    }

    @Test
    fun `filteredBlob is the content a checkout would write, or that it is too large`() {
        repo.write(".gitattributes", "*.up filter=up")
        repo.git("config", "filter.up.smudge", "tr a-z A-Z")
        repo.write("x.up", "abc")
        repo.git("add", ".")
        repo.git("commit", "-q", "-m", "filtered")
        val blob = repo.git("rev-parse", "HEAD:x.up")

        val plain = git.filteredBlob(root, repo.git("rev-parse", "$second:a.txt"), "a.txt", 100)
        val filtered = git.filteredBlob(root, blob, "x.up", 100)

        assertContentEquals("second\n".toByteArray(), assertIs<FilteredBlob.Content>(plain).bytes)
        assertContentEquals("ABC\n".toByteArray(), assertIs<FilteredBlob.Content>(filtered).bytes)
        assertEquals(FilteredBlob.TooLarge, git.filteredBlob(root, blob, "x.up", 3), "4 bytes after the filter")
        val exact = git.filteredBlob(root, blob, "x.up", 4)
        assertContentEquals("ABC\n".toByteArray(), assertIs<FilteredBlob.Content>(exact).bytes)
    }

    @Test
    fun `filteredBlob reads a blob of exactly the size limit, also in a repository with long object names`() {
        val long = sha256()
        long.write("big.txt", "x".repeat(99))
        long.git("add", ".")
        long.git("commit", "-q", "-m", "big")
        val blob = long.git("rev-parse", "HEAD:big.txt")
        assertEquals(64, blob.length)

        // 64 characters of name, " blob 100\n", the 100 bytes and a line break: more than 64 bytes of slack.
        assertContentEquals(
            ("x".repeat(99) + "\n").toByteArray(),
            assertIs<FilteredBlob.Content>(git.filteredBlob(long.root, blob, "big.txt", 100)).bytes,
        )
        assertEquals(FilteredBlob.TooLarge, git.filteredBlob(long.root, blob, "big.txt", 99))
    }

    @Test
    fun `filteredBlob of an empty blob is empty content, of a huge one it stops git`() {
        repo.write("empty.txt", "")
        repo.root.resolve("empty.txt").writeBytes(ByteArray(0))
        repo.git("add", ".")
        repo.git("commit", "-q", "-m", "empty")
        val empty = repo.git("rev-parse", "HEAD:empty.txt")
        repo.root.resolve("huge.txt").writeBytes(ByteArray(10_000) { 'h'.code.toByte() })
        val huge = repo.git("hash-object", "-w", "huge.txt")

        val nothing = git.filteredBlob(root, empty, "empty.txt", 10)
        assertContentEquals(ByteArray(0), assertIs<FilteredBlob.Content>(nothing).bytes)
        assertEquals(FilteredBlob.TooLarge, git.filteredBlob(root, huge, "huge.txt", 10))
        assertEquals(FilteredBlob.TooLarge, git.filteredBlob(root, huge, "huge.txt", 9_990))
    }

    @Test
    fun `filteredBlob has no answer for what is no blob, a path with a line break or a failing git`() {
        val tree = repo.git("rev-parse", "$second:dir")
        val blob = repo.git("rev-parse", "$second:a.txt")

        assertNull(git.filteredBlob(root, tree, "dir", 100), "a tree")
        assertNull(git.filteredBlob(root, "f".repeat(40), "a.txt", 100), "missing")
        assertNull(git.filteredBlob(root, blob, "a\ntxt", 100))
        assertNull(unavailable.filteredBlob(root, blob, "a.txt", 100))
        assertNull(vanishedGit(folder).filteredBlob(root, blob, "a.txt", 100))
        assertNull(failing("cat-file").filteredBlob(root, blob, "a.txt", 100))
    }

    @Test
    fun `filteredBlob has no answer for an output that git ended with an error or that is not whole`() {
        val name = "b".repeat(40)

        fun reply(
            output: String,
            exit: Int = 0,
            hold: Boolean = false,
        ) = answering("cat-file", "printf '$output'", exit, hold).filteredBlob(root, name, "p", 100)

        assertContentEquals("x".toByteArray(), assertIs<FilteredBlob.Content>(reply("$name blob 1\\nx\\n")).bytes)
        assertNull(reply("$name blob 1\\nx\\n", exit = 1))
        assertNull(reply("$name blob 1\\nx\\n", hold = true))
        assertNull(reply("$name blob 1\\nx"), "no line break at the end")
        assertNull(reply("$name blob 1\\n"), "no content and no line break after it")
        assertNull(reply("$name blob 1"), "no header line break")
        assertNull(reply("\\nx\\n"), "no header")
        assertNull(reply("c".repeat(40) + " blob 1\\nx\\n"), "another object")
        assertNull(reply("$name tree 1\\nx\\n"), "not a blob")
    }

    @Test
    fun `diffFiles are git's hunks of two texts compared as they are`() {
        folder.resolve("cmp").createDirectories()
        val cmp = folder.resolve("cmp")
        cmp.resolve("o.txt").writeText("one\ntwo\n")
        cmp.resolve("c.txt").writeText("one\nthree\n")

        val diff = git.diffFiles(cmp, "o.txt", "c.txt", null)

        val index = diff?.lines()?.get(1)
        assertEquals(
            "diff --git a/o.txt b/c.txt\n$index\n--- a/o.txt\n+++ b/c.txt\n@@ -2 +2 @@ one\n-two\n+three\n",
            diff,
        )
        assertEquals("", git.diffFiles(cmp, "o.txt", "o.txt", null), "equal texts: exit 0, nothing")
        assertNull(unavailable.diffFiles(cmp, "o.txt", "c.txt", null))
        assertNull(vanishedGit(folder).diffFiles(cmp, "o.txt", "c.txt", null))
        assertNull(git.diffFiles(cmp, "o.txt", "missing.txt", null), "git exits with 1 and no hunk")
        val nowhere = folder.resolve("no-such-folder")
        assertNull(git.diffFiles(nowhere, "o.txt", "c.txt", null), "no room for the attributes")
        assertNull(git.diffFiles(cmp, "o.txt", "c.txt", null, cancellation = GitCancellation().apply { cancel() }))
    }

    @Test
    fun `diffFiles ignores all whitespace on request and takes the algorithm given`() {
        val cmp = folder.resolve("cmp").createDirectories()
        cmp.resolve("o.txt").writeText("a b\n")
        cmp.resolve("c.txt").writeText("a  b\n")
        val log = folder.resolve("calls")
        val recording = recordingGit(folder, log)

        assertTrue("@@ -1 +1 @@" in assertNotNull(git.diffFiles(cmp, "o.txt", "c.txt", null)))
        assertEquals("", git.diffFiles(cmp, "o.txt", "c.txt", null, ignoreWhitespace = true))
        assertNotNull(recording.diffFiles(cmp, "o.txt", "c.txt", "patience"))
        assertNotNull(recording.diffFiles(cmp, "o.txt", "c.txt", null))

        val calls = recordedCalls(log).filter { "--no-index" in it }
        assertTrue("-c diff.algorithm=patience " in calls[0], calls[0])
        assertTrue("diff.algorithm" !in calls[1], calls[1])
    }

    @Test
    fun `diffFiles takes nothing from the configuration, a repository above or the attributes of the user`() {
        val outer = TestRepository(folder.resolve("outer"))
        outer.git("config", "diff.noprefix", "true")
        val cmp = outer.root.resolve("sub").createDirectories()
        cmp.resolve("o.txt").writeBytes("a\r\nb\r\n".toByteArray())
        cmp.resolve("c.txt").writeBytes("a\nb\n".toByteArray())
        val attributes = folder.resolve("global-attributes").apply { writeText("* ident\n") }
        // Configuration from the environment, which GitCli keeps: converts line endings, and reads other attributes.
        val configured =
            GitCli(
                "git",
                testGitEnvironment() +
                    mapOf(
                        "GIT_CONFIG_COUNT" to "2",
                        "GIT_CONFIG_KEY_0" to "core.autocrlf",
                        "GIT_CONFIG_VALUE_0" to "true",
                        "GIT_CONFIG_KEY_1" to "core.attributesFile",
                        "GIT_CONFIG_VALUE_1" to attributes.toString(),
                    ),
            )

        val diff = assertNotNull(configured.diffFiles(cmp, "o.txt", "c.txt", null))

        // The CR is a difference; the repository above is not searched (it would take away the a/ and b/).
        assertTrue(diff.startsWith("diff --git a/o.txt b/c.txt\n"), diff)
        assertTrue("-a\r\n" in diff, diff)
    }

    @Test
    fun `diffFiles has the attributes empty, so that a file of that name in the folder says nothing`() {
        val cmp = folder.resolve("cmp").createDirectories()
        // `ident` collapses "$Id: …$": with it, the two texts are the same.
        cmp.resolve("attributes").writeText("* ident\n")
        cmp.resolve("o.txt").writeText("\$Id: abc\$\n")
        cmp.resolve("c.txt").writeText("\$Id: def\$\n")

        assertTrue("@@ -1 +1 @@" in assertNotNull(git.diffFiles(cmp, "o.txt", "c.txt", null)))
    }

    @Test
    fun `diffFiles has no answer for an output that is not complete, or for other exit values`() {
        val cmp = folder.resolve("cmp").createDirectories()
        val hunk = "diff --git a/o b/c\\n@@ -1 +1 @@\\n-a\\n+b\\n"
        val config = "*' config '*) exit 1;;"

        fun reply(
            exit: Int,
            output: String = hunk,
            hold: Boolean = false,
        ): String? {
            val held = if (hold) "(sleep 3 &); sleep 0.3;" else ""
            val body = "case \" \$* \" in $config *' diff '*) printf '$output'; $held exit $exit;; esac"
            return fakeGit(body).diffFiles(cmp, "o", "c", null)
        }

        assertEquals(hunk.replace("\\n", "\n"), reply(0))
        assertEquals(hunk.replace("\\n", "\n"), reply(1))
        assertNull(reply(2))
        assertNull(reply(128))
        assertNull(reply(1, hold = true))
        assertNull(reply(0, hold = true))
        assertNull(reply(1, output = "@@ at the start\\n"), "a hunk is after a line break")
        assertEquals("", reply(0, output = ""))
    }
}
