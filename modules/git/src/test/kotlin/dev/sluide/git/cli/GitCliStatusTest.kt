package dev.sluide.git.cli

import dev.sluide.git.failingGit
import dev.sluide.git.recordedCalls
import dev.sluide.git.recordingGit
import dev.sluide.git.testGitEnvironment
import dev.sluide.git.unavailableGit
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The status readers of [GitCli] (`GitCliStatus.kt`) with the real git. */
class GitCliStatusTest {
    @TempDir
    lateinit var folder: Path

    private val git = GitCli("git", testGitEnvironment())
    private lateinit var repo: TestRepository
    private val root get() = repo.root
    private val calls get() = folder.resolve("calls.log")

    @BeforeEach
    fun createRepository() {
        assumeTrue(git.available, "git is not installed")
        repo = TestRepository(folder.resolve("repo"))
        repo.commit("first", "a.txt", "one\ntwo")
        repo.commit("hidden", "..hidden.txt", "hidden")
    }

    @Test
    fun `status is git's porcelain output with the branch header, ignored files included`() {
        repo.write(".gitignore", "ignored.txt")
        repo.write("ignored.txt", "i")
        repo.write("untracked.txt", "u")

        val status = git.status(root)

        assertNotNull(status)
        assertTrue(status.startsWith("# branch.oid ${repo.head()}\u0000# branch.head main\u0000"), status)
        assertTrue("? untracked.txt\u0000" in status && "! ignored.txt\u0000" in status, status)
        assertNull(unavailableGit(folder).status(root))
        assertNull(failingGit(folder, "status").status(root))
    }

    @Test
    fun `headContent is the file at HEAD, not the working tree's`() {
        repo.write("a.txt", "changed in the working tree")

        assertEquals("one\ntwo\n", git.headContent(root, root.resolve("a.txt"))?.decodeToString())
        assertEquals("hidden\n", git.headContent(root, root.resolve("..hidden.txt"))?.decodeToString())
        assertNull(git.headContent(root, root.resolve("not-in-head.txt")))
        assertNull(unavailableGit(folder).headContent(root, root.resolve("a.txt")))
    }

    @Test
    fun `a file outside the repository, or the repository itself, is no file for git to read`() {
        val recording = recordingGit(folder, calls)

        assertNull(recording.headContent(root, root.resolve("../outside.txt")))
        assertNull(recording.headContent(root, root))
        assertNull(recording.diffToHead(root, root.resolve("../outside.txt")))
        assertNull(recording.diffToHead(root, root))
        assertNull(recording.diffToHead(root, root.parent))
        assertEquals(emptyList(), recordedCalls(calls), "git did not run")
    }

    @Test
    fun `diffToHead is the zero-context diff of the file against HEAD`() {
        repo.write("a.txt", "one\nchanged")
        repo.write("*.txt", "a file named like a pattern")
        repo.write("..hidden.txt", "hidden\nmore")

        val diff = git.diffToHead(root, root.resolve("a.txt"))

        assertNotNull(diff)
        assertTrue(diff.contains("@@ -1,2 +1,2 @@") || diff.contains("@@ -2 +2 @@"), diff)
        assertTrue("+changed" in diff && "-two" in diff, diff)
        assertTrue("b.txt" !in diff && "hidden" !in diff, "only the file asked for: $diff")
        assertTrue("+more" in git.diffToHead(root, root.resolve("..hidden.txt")).orEmpty())
        assertEquals(
            "",
            git.diffToHead(root, root.resolve("*.txt")),
            "no diff for a file git does not know, and no pattern",
        )
        assertNull(unavailableGit(folder).diffToHead(root, root.resolve("a.txt")))
        assertNull(failingGit(folder, "diff").diffToHead(root, root.resolve("a.txt")))
    }

    @Test
    fun `trackedChangeCount counts the changed tracked files, staged or not, each once`() {
        assertEquals(0, git.trackedChangeCount(root))

        repo.write("a.txt", "changed")
        repo.write("..hidden.txt", "changed")
        repo.write("staged.txt", "s")
        repo.git("add", "staged.txt")
        repo.write("untracked.txt", "u")
        repo.write("sp ace \"q\".txt", "s")
        repo.git("add", "sp ace \"q\".txt")

        assertEquals(4, git.trackedChangeCount(root))

        repo.git("mv", "a.txt", "renamed.txt")

        assertEquals(5, git.trackedChangeCount(root), "a rename is a deletion and an addition")
        assertNull(unavailableGit(folder).trackedChangeCount(root))
        assertNull(failingGit(folder, "status").trackedChangeCount(root))
    }
}
