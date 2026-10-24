package dev.sluide.gitlog.area

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.gitlog.history.FileHistoryKey
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryKeysTest {
    private val root = Path.of("/repo")

    @Test
    fun `a file below the repository has a history from HEAD, by its path relative to the root`() {
        assertEquals(FileHistoryKey("a.txt"), historyKey(root, Path.of("/repo/a.txt"), isDirectory = false))
        assertEquals(
            FileHistoryKey("src/main/App.kt"),
            historyKey(root, Path.of("/repo/src/../src/main/App.kt"), isDirectory = false),
            "normalized",
        )
    }

    @Test
    fun `a folder, the repository itself and a file outside of it have none`() {
        assertNull(historyKey(root, Path.of("/repo/src"), isDirectory = true))
        assertNull(historyKey(root, Path.of("/repo"), isDirectory = false), "the root itself")
        assertNull(historyKey(root, Path.of("/repo"), isDirectory = true))
        assertNull(historyKey(root, Path.of("/other/a.txt"), isDirectory = false))
        assertNull(historyKey(root, Path.of("/repo/../a.txt"), isDirectory = false))
    }

    private fun commit(vararg parents: String) =
        GitCommit("c2", parents.toList(), "A", "a@example.com", Instant.EPOCH, "Subject")

    @Test
    fun `the history up to a commit starts there, for a deleted file at the first parent`() {
        assertEquals(
            FileHistoryKey("a.txt", "c2"),
            historyKeyUpTo(commit("c1"), GitChangedFile(GitChange.MODIFIED, "a.txt")),
        )
        assertEquals(
            FileHistoryKey("a.txt", "c2"),
            historyKeyUpTo(commit(), GitChangedFile(GitChange.ADDED, "a.txt")),
            "a root commit",
        )
        assertEquals(
            FileHistoryKey("a.txt", "c1"),
            historyKeyUpTo(commit("c1", "side"), GitChangedFile(GitChange.DELETED, "a.txt")),
        )
    }

    @Test
    fun `a deleted file in a commit without a parent has no history to show`() {
        assertNull(historyKeyUpTo(commit(), GitChangedFile(GitChange.DELETED, "a.txt")))
    }
}
