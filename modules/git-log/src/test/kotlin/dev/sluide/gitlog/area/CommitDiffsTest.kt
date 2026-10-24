package dev.sluide.gitlog.area

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitHunk
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What keeps a diff from being shown, and which file is compared with the working tree, without a view. */
class CommitDiffsTest {
    private val hunks = listOf(GitHunk(0..0, 0..0))
    private val changed = GitFileDiff.Text("old\n", "new\n", hunks)
    private val same = GitFileDiff.Text("same\n", "same\n", emptyList())

    @Test
    fun `a commit's diff that cannot be read, is binary or too large says so`() {
        assertEquals("The diff could not be read.", commitDiffMessage("a.txt", GitChange.MODIFIED, null))
        assertEquals("a.png is a binary file.", commitDiffMessage("a.png", GitChange.MODIFIED, GitFileDiff.Binary))
        assertEquals(
            "a.sql is too large to compare.",
            commitDiffMessage("a.sql", GitChange.ADDED, GitFileDiff.TooLarge),
        )
    }

    @Test
    fun `a submodule says where it pointed, was added or removed`() {
        val old = "0123456789abcdef"
        val new = "fedcba9876543210"
        assertEquals(
            "lib is a submodule: 01234567 → fedcba98.",
            commitDiffMessage("lib", GitChange.MODIFIED, GitFileDiff.Submodule(old, new)),
        )
        assertEquals(
            "lib is a submodule, added at fedcba98.",
            commitDiffMessage("lib", GitChange.ADDED, GitFileDiff.Submodule(null, new)),
        )
        assertEquals(
            "lib is a submodule, removed.",
            commitDiffMessage("lib", GitChange.DELETED, GitFileDiff.Submodule(old, null)),
        )
    }

    @Test
    fun `a text that did not change is told for a change that has content on both sides, else it is shown`() {
        for (change in listOf(GitChange.RENAMED, GitChange.COPIED, GitChange.MODIFIED, GitChange.TYPE_CHANGED)) {
            assertEquals("a.txt has no content changes.", commitDiffMessage("a.txt", change, same), "$change")
        }
        // An added or deleted empty file still has something to show: that it is there or gone.
        assertNull(commitDiffMessage("a.txt", GitChange.ADDED, same))
        assertNull(commitDiffMessage("a.txt", GitChange.DELETED, same))
        assertNull(commitDiffMessage("a.txt", GitChange.MODIFIED, changed))
    }

    @Test
    fun `a working-tree diff that cannot be read, is binary, too large or a submodule says so`() {
        val file = GitChangedFile(GitChange.MODIFIED, "a.txt")
        assertEquals("The diff could not be read.", workingTreeDiffMessage("a.txt", file, null))
        assertEquals("a.png is a binary file.", workingTreeDiffMessage("a.png", file, GitFileDiff.Binary))
        assertEquals("a.sql is too large to compare.", workingTreeDiffMessage("a.sql", file, GitFileDiff.TooLarge))
        assertEquals(
            "lib is a submodule.",
            workingTreeDiffMessage("lib", file, GitFileDiff.Submodule("0123456789abcdef", "fedcba9876543210")),
        )
        assertEquals("lib is a submodule.", workingTreeDiffMessage("lib", file, GitFileDiff.Submodule(null, "ab")))
    }

    @Test
    fun `a working-tree text equal to the file is the same, unless the file was added or deleted there`() {
        for (change in listOf(GitChange.MODIFIED, GitChange.RENAMED, GitChange.COPIED, GitChange.TYPE_CHANGED)) {
            assertEquals(
                "a.txt is the same in the working tree.",
                workingTreeDiffMessage("a.txt", GitChangedFile(change, "a.txt"), same),
                "$change",
            )
        }
        assertNull(workingTreeDiffMessage("a.txt", GitChangedFile(GitChange.ADDED, "a.txt"), same))
        assertNull(workingTreeDiffMessage("a.txt", GitChangedFile(GitChange.DELETED, "a.txt"), same))
        assertNull(workingTreeDiffMessage("a.txt", GitChangedFile(GitChange.MODIFIED, "a.txt"), changed))
    }

    @Test
    fun `Compare with Local compares a file as modified, renamed, deleted or added`() {
        fun compared(
            file: GitChangedFile,
            inWorkingTree: Boolean = true,
        ) = comparedWithWorkingTree("src/App.kt", file) { inWorkingTree }

        assertEquals(
            GitChangedFile(GitChange.MODIFIED, "src/App.kt"),
            compared(GitChangedFile(GitChange.MODIFIED, "src/App.kt")),
        )
        assertEquals(
            GitChangedFile(GitChange.RENAMED, "src/App.kt", "src/Old.kt"),
            compared(GitChangedFile(GitChange.RENAMED, "src/Old.kt", "src/Older.kt")),
            "at another path in the commit",
        )
        assertEquals(
            GitChangedFile(GitChange.DELETED, "src/Old.kt"),
            compared(GitChangedFile(GitChange.MODIFIED, "src/Old.kt"), inWorkingTree = false),
            "gone from the working tree",
        )
        assertEquals(
            GitChangedFile(GitChange.ADDED, "src/App.kt"),
            compared(GitChangedFile(GitChange.DELETED, "src/App.kt")),
            "not at the commit: all of the file is new",
        )
    }

    @Test
    fun `a file that is deleted at the commit is not looked for in the working tree`() {
        var asked = false
        comparedWithWorkingTree("a.txt", GitChangedFile(GitChange.DELETED, "a.txt")) {
            asked = true
            true
        }
        assertEquals(false, asked)
    }

    @Test
    fun `a name the JVM cannot turn into a path counts as there, and has no local file`() {
        val root = Path.of("/repo")
        assertTrue(existsInWorkingTree(root, "a.txt") { true })
        assertEquals(false, existsInWorkingTree(root, "a.txt") { false })
        assertTrue(existsInWorkingTree(root, "a.txt") { throw InvalidPathException("a.txt", "malformed") })

        assertEquals(root.resolve("src/a.txt"), localPath(root, "src/a.txt"))
        assertNull(localPath(null, "a.txt"))
        assertNull(localPath(root, "a\u0000b.txt"), "a NUL byte is no path")
    }
}
