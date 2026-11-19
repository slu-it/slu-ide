package dev.sluide.git.cli

import dev.sluide.git.GitCancellation
import dev.sluide.git.failingGit
import dev.sluide.git.fakeGit
import dev.sluide.git.testGitEnvironment
import dev.sluide.git.unavailableGit
import dev.sluide.git.vanishedGit
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * The index readers of [GitCli] (`GitCliIndex.kt`) with the real git: what they answer for a repository, and that a
 * git that fails, or is not available, gives no answer even where the real one would.
 */
class GitCliIndexTest {
    @TempDir
    lateinit var folder: Path

    private val git = GitCli("git", testGitEnvironment())
    private lateinit var repo: TestRepository
    private val root get() = repo.root
    private lateinit var first: String

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo = TestRepository(folder.resolve("repo"))
        repo.write("a.txt", "a")
        repo.write("b.txt", "b")
        repo.git("add", ".")
        repo.git("commit", "-q", "-m", "first")
        first = repo.head()
    }

    private val unavailable get() = unavailableGit(folder)

    private fun failing(word: String) = failingGit(folder, word)

    /** Conflicts in `a.txt`: main and `other` change it differently from [first]; returns the commit of `other`. */
    private fun conflicting(): String {
        repo.git("checkout", "-q", "-b", "other")
        val other = repo.commit("other", "a.txt")
        repo.git("checkout", "-q", "main")
        repo.commit("main", "a.txt")
        return other
    }

    @Test
    fun `the paths in the index, the untracked ones, and all others`() {
        repo.write("with space.txt", "x")
        repo.git("add", "with space.txt")
        repo.write("untracked/new.txt", "x")
        repo.write("ignored.log", "x")
        repo.write(".gitignore", "*.log")

        assertEquals(listOf("a.txt", "b.txt", "with space.txt"), git.indexedPaths(root))
        assertEquals(listOf(".gitignore", "untracked/new.txt"), git.untrackedPaths(root))
        assertEquals(listOf(".gitignore", "ignored.log", "untracked/new.txt"), git.otherPaths(root))
    }

    @Test
    fun `a listing that git cannot give or that is cancelled is unknown`() {
        assertNull(unavailable.indexedPaths(root))
        assertNull(unavailable.untrackedPaths(root))
        assertNull(unavailable.otherPaths(root))
        assertNull(failing("ls-files").indexedPaths(root))
        assertNull(failing("ls-files").untrackedPaths(root))
        assertNull(failing("ls-files").otherPaths(root))
        assertNull(git.untrackedPaths(root, GitCancellation().apply { cancel() }))
        assertEquals(emptyList(), TestRepository(folder.resolve("empty")).let { git.indexedPaths(it.root) })
    }

    @Test
    fun `changedPaths are the paths whose working tree file or index differ, each path of a rename`() {
        repo.write("a.txt", "changed")
        repo.git("mv", "b.txt", "moved.txt")

        assertEquals(setOf("a.txt"), git.changedPaths(root, staged = false))
        assertEquals(setOf("b.txt", "moved.txt"), git.changedPaths(root, staged = true))
        assertNull(unavailable.changedPaths(root, staged = false))
        assertNull(failing("diff").changedPaths(root, staged = true))
    }

    @Test
    fun `changedPaths of the staged changes leave out the unmerged paths`() {
        conflicting()
        repo.gitStops("merge", "-q", "other")
        repo.write("b.txt", "changed")

        // a.txt is unmerged: its stages come from commits, not from a change to the index.
        assertEquals(emptySet(), git.changedPaths(root, staged = true))
    }

    @Test
    fun `changedPaths of the working tree list an unmerged path only while its file differs from ours`() {
        conflicting()
        repo.gitStops("merge", "-q", "other")

        // git prints a U record and an M record (stage 2 against the file) for it; --diff-filter=u drops the U one.
        assertEquals(setOf("a.txt"), git.changedPaths(root, staged = false), "the file has the conflict markers")

        repo.git("checkout", "--ours", "--", "a.txt")

        assertEquals(emptySet(), git.changedPaths(root, staged = false), "the file is ours, still unmerged")
        assertEquals(listOf("a.txt"), git.unmergedPaths(root))
    }

    @Test
    fun `changedPaths name HEAD as a revision even if a file of that name exists`() {
        repo.write("HEAD", "a file")
        repo.git("add", "HEAD")

        // Without `--`, git refuses the ambiguous argument HEAD.
        assertEquals(setOf("HEAD"), git.changedPaths(root, staged = true))
    }

    @Test
    fun `indexChanges are git's raw records for how the index differs from a commit`() {
        repo.git("rm", "-q", "b.txt")
        repo.write("c.txt", "c")
        repo.git("add", "c.txt")
        val blob = repo.git("rev-parse", "$first:b.txt")
        val zero = "0".repeat(40)

        val changes = git.indexChanges(root, first)

        assertEquals(
            ":100644 000000 $blob $zero D\u0000b.txt\u0000:000000 100644 $zero ${repo.git(
                "rev-parse",
                ":c.txt",
            )} A\u0000c.txt\u0000",
            changes,
        )
        assertNull(unavailable.indexChanges(root, first))
        assertNull(failing("diff-index").indexChanges(root, first))
        assertNull(git.indexChanges(root, "--name-only"))
    }

    @Test
    fun `indexChanges does not give an option to git, which would write a file before it fails`() {
        val victim = folder.resolve("victim.txt").apply { writeText("keep me\n") }

        // git diff-index --output=<file> opens (and empties) the file while it reads its options, then exits with 129.
        assertNull(git.indexChanges(root, "--output=$victim"))

        assertEquals("keep me\n", victim.readText())
    }

    @Test
    fun `mergeTree is the tree of the merge, also with conflicts`() {
        repo.git("checkout", "-q", "-b", "side", first)
        repo.commit("side", "b.txt")
        repo.git("checkout", "-q", "main")
        val ours = repo.commit("main", "a.txt")
        val theirs = repo.git("rev-parse", "side")

        val clean = assertNotNullTree(git.mergeTree(root, first, ours, theirs))
        assertEquals(repo.git("rev-parse", "$ours^{tree}").length, clean.length)
        assertEquals(clean, repo.git("merge-tree", "--write-tree", "--merge-base=$first", ours, theirs))
        assertNull(unavailable.mergeTree(root, first, ours, theirs))
        assertNull(failing("merge-tree").mergeTree(root, first, ours, theirs))
        assertNull(git.mergeTree(root, first, ours, "no-such-revision"), "git fails with exit 128")
    }

    private fun assertNotNullTree(tree: String?): String {
        kotlin.test.assertNotNull(tree)
        return tree
    }

    @Test
    fun `mergeTree gives the tree of a merge with conflicts too, which git signals with exit 1`() {
        val theirs = conflicting()
        val ours = repo.head()

        val tree = git.mergeTree(root, first, ours, theirs)

        kotlin.test.assertNotNull(tree)
        val process = repo.gitStops("merge-tree", "--write-tree", "--no-messages", "--merge-base=$first", ours, theirs)
        assertEquals(tree, process.lines().first())
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "9", "a", "f", "0123456789abcdef"])
    fun `mergeTree keeps a tree name of lowercase hexadecimal digits`(name: String) {
        assertEquals(
            name,
            fakeGit(
                folder,
                "case \" \$* \" in *' merge-tree '*) printf '%s\\nfile\\n' $name;; esac",
            ).mergeTree(root, "a", "b", "c"),
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["/", ":", "`", "g", "A", "ab/", "a b"])
    fun `mergeTree gives no tree for a first line that is no lowercase hexadecimal name`(line: String) {
        val answering = fakeGit(folder, "case \" \$* \" in *' merge-tree '*) printf '%s\\n' '$line';; esac")

        assertNull(answering.mergeTree(root, "a", "b", "c"))
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun `mergeTree takes exit values 0 and 1 for an answer`(exit: Int) {
        assertEquals(
            "abc",
            fakeGit(
                folder,
                "case \" \$* \" in *' merge-tree '*) echo abc; exit $exit;; esac",
            ).mergeTree(root, "a", "b", "c"),
        )
    }

    @ParameterizedTest
    @ValueSource(ints = [2, 128])
    fun `mergeTree takes other exit values for a failure, whatever git printed`(exit: Int) {
        assertNull(
            fakeGit(
                folder,
                "case \" \$* \" in *' merge-tree '*) echo abc; exit $exit;; esac",
            ).mergeTree(root, "a", "b", "c"),
        )
    }

    @Test
    fun `mergeTree has no answer for an output that is not complete or empty`() {
        val held = "case \" \$* \" in *' merge-tree '*) echo abc; (sleep 3 &); sleep 0.3; exit 0;; esac"
        assertNull(fakeGit(folder, held).mergeTree(root, "a", "b", "c"))
        assertNull(fakeGit(folder, "case \" \$* \" in *' merge-tree '*) exit 0;; esac").mergeTree(root, "a", "b", "c"))
    }

    @Test
    fun `mergeTree takes no revision for an option`() {
        // git merge-tree --write-tree takes exactly two revisions: an option in the place of one fails by itself.
        assertNull(git.mergeTree(root, first, "--stdin", first))
        assertNull(git.mergeTree(root, first, first, "--stdin"))
        assertNull(git.mergeTree(root, "-x", first, first))
    }

    @Test
    fun `unmerged entries and paths are those of the conflicts, each path once`() {
        conflicting()
        repo.gitStops("merge", "-q", "other")

        val entries = git.unmergedEntries(root)

        assertEquals(listOf(1, 2, 3), entries?.map { it.stage })
        assertEquals(listOf("a.txt"), entries?.map { it.path }?.distinct())
        assertEquals("100644", entries?.first()?.mode)
        assertEquals(repo.git("rev-parse", "$first:a.txt"), entries?.first()?.objectName)
        assertEquals(listOf("a.txt"), git.unmergedPaths(root))
        assertEquals(emptyList(), TestRepository(folder.resolve("empty")).let { git.unmergedPaths(it.root) })
        assertNull(unavailable.unmergedEntries(root))
        assertNull(failing("ls-files").unmergedEntries(root))
        assertNull(failing("ls-files").unmergedPaths(root))
    }

    @Test
    fun `unmerged records that are not git's are no answer`() {
        // <mode> <object> <stage> TAB <path>: two fields, or a stage that is no number, is not that.
        assertNull(fakeGit(folder, "printf '100644 abc\\tp\\000'").unmergedEntries(root))
        assertNull(fakeGit(folder, "printf '100644 abc x\\tp\\000'").unmergedEntries(root))
        assertNull(fakeGit(folder, "printf '100644 abc 1 2\\tp\\000'").unmergedEntries(root))
        assertEquals(
            listOf(UnmergedEntry("p q", 2, "100644", "abc")),
            fakeGit(folder, "printf 'ignored\\000100644 abc 2\\tp q\\000'").unmergedEntries(root),
        )
    }

    @Test
    fun `missingFiles are the tracked paths deleted from the working tree`() {
        repo.root.resolve("a.txt").deleteExisting()
        repo.write("with space.txt", "x")
        repo.git("add", "with space.txt")
        repo.root.resolve("with space.txt").deleteExisting()

        assertEquals(setOf("a.txt", "with space.txt"), git.missingFiles(root))
        assertNull(unavailable.missingFiles(root))
        assertNull(failing("ls-files").missingFiles(root))
        assertEquals(emptySet(), TestRepository(folder.resolve("empty")).let { git.missingFiles(it.root) })
    }

    @Test
    fun `attributes are the values git names for each path and attribute`() {
        repo.write(".gitattributes", "*.txt text eol=lf\nb.txt -text")

        assertEquals(
            mapOf(
                "a.txt" to mapOf("text" to "set", "eol" to "lf", "diff" to "unspecified"),
                "b.txt" to mapOf("text" to "unset", "eol" to "lf", "diff" to "unspecified"),
                "köln.md" to mapOf("text" to "unspecified", "eol" to "unspecified", "diff" to "unspecified"),
            ),
            git.attributes(root, listOf("a.txt", "b.txt", "köln.md"), listOf("text", "eol", "diff")),
        )
        assertEquals(emptyMap(), git.attributes(root, emptyList(), listOf("text")))
        // No path, no call: also where git would fail.
        assertEquals(emptyMap(), failing("check-attr").attributes(root, emptyList(), listOf("text")))
        assertNull(failing("check-attr").attributes(root, listOf("a.txt"), listOf("text")))
        assertNull(unavailable.attributes(root, listOf("a.txt"), listOf("text")))
        assertNull(unavailable.attributes(root, emptyList(), listOf("text")))
    }

    @Test
    fun `indexMatchesHead tells a staged change, nothing to compare and a failure apart`() {
        assertEquals(true, git.indexMatchesHead(root))
        repo.write("a.txt", "changed")
        assertEquals(true, git.indexMatchesHead(root), "an unstaged change is not in the index")
        repo.git("add", "a.txt")
        assertEquals(false, git.indexMatchesHead(root))
        assertNull(TestRepository(folder.resolve("empty")).let { git.indexMatchesHead(it.root) }, "no HEAD commit")
        assertNull(unavailable.indexMatchesHead(root))
        assertNull(failing("diff-index").indexMatchesHead(root))
    }

    @Test
    fun `gitPaths are where git keeps its files, resolved against the root`() {
        assertEquals(
            listOf(root.resolve(".git/MERGE_HEAD"), root.resolve(".git/index")),
            git.gitPaths(root, listOf("MERGE_HEAD", "index")),
        )
        assertNull(unavailable.gitPaths(root, listOf("index")))
        assertNull(failing("rev-parse").gitPaths(root, listOf("index")))
        assertNull(vanishedGit(folder).gitPaths(root, listOf("index")))
    }

    @Test
    fun `gitPaths need one path per name, blank lines left out`() {
        val names = listOf("one", "two")

        fun paths(output: String) =
            fakeGit(folder, "case \" \$* \" in *' rev-parse '*) printf '$output';; esac").gitPaths(root, names)

        assertEquals(listOf(root.resolve("a"), root.resolve("b")), paths("a\\n\\nb\\n"))
        assertNull(paths("a\\n"))
        assertNull(paths("a\\nb\\nc\\n"))
        assertNull(paths(""))
    }

    @Test
    fun `gitPaths of a name the JVM cannot make a path of are unknown`() {
        // A POSIX locale cannot name this path (InvalidPathException); a UTF-8 one can. The answer follows the JVM.
        val named =
            try {
                listOf(root.resolve("k\u00f6ln"))
            } catch (_: java.nio.file.InvalidPathException) {
                null
            }

        val paths =
            fakeGit(
                folder,
                "case \" \$* \" in *' rev-parse '*) printf 'k\\303\\266ln\\n';; esac",
            ).gitPaths(root, listOf("x"))

        assertEquals(named, paths)
    }

    @Test
    fun `a git that vanishes or hangs gives no tree and no answer for the index`() {
        val vanished = vanishedGit(folder)
        assertNull(vanished.mergeTree(root, first, first, first))
        assertNull(vanished.indexMatchesHead(root))

        val slow = 300.milliseconds
        val hangs = fakeGit(folder, "case \" \$* \" in *' merge-tree '*|*' diff-index '*) sleep 30;; esac")
        assertNull(hangs.mergeTree(root, first, first, first, slow))
        assertNull(hangs.indexMatchesHead(root, slow))
    }

    @Test
    fun `indexMatchesHead takes exit values 0 and 1 for an answer`() {
        fun matches(exit: Int) =
            fakeGit(folder, "case \" \$* \" in *' diff-index '*) exit $exit;; esac").indexMatchesHead(root)

        assertEquals(true, matches(0))
        assertEquals(false, matches(1))
        assertNull(matches(2))
        assertNull(matches(128))
    }
}
