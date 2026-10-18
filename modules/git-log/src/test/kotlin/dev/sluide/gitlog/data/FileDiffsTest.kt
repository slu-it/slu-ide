package dev.sluide.gitlog.data

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitFileDiff
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class FileDiffsTest {
    private val repo = Path.of("/repo")
    private val background = ArrayDeque<Runnable>()
    private val dispatched = ArrayDeque<Runnable>()
    private val loads = mutableListOf<String>()
    private val diffs =
        FileDiffs(
            { _, commit, parent, file ->
                loads += "$commit:$parent:${file.path}"
                GitFileDiff.Text("", file.path, emptyList())
            },
            background::addLast,
            dispatched::addLast,
        )
    private val handled = mutableListOf<GitFileDiff?>()

    private val commit = GitCommit("c", listOf("p1", "p2"), "A", "a@example.com", Instant.EPOCH, "S")

    private fun request(path: String) =
        diffs.request(repo, commit, GitChangedFile(GitChange.MODIFIED, path)) { handled += it }

    private fun settle() {
        while (background.isNotEmpty() || dispatched.isNotEmpty()) {
            while (background.isNotEmpty()) background.removeFirst().run()
            while (dispatched.isNotEmpty()) dispatched.removeFirst().run()
        }
    }

    @Test
    fun `reads the diff against the first parent, the latest request wins`() {
        request("a.txt")
        background.removeFirst().run()
        request("b.txt")
        request("c.txt")
        settle()

        assertEquals(listOf("c:p1:a.txt", "c:p1:c.txt"), loads, "b's request was superseded before it ran")
        assertEquals(listOf<GitFileDiff?>(GitFileDiff.Text("", "c.txt", emptyList())), handled)
    }

    @Test
    fun `nothing is handled after close`() {
        request("a.txt")
        diffs.close()
        settle()

        assertEquals(emptyList(), handled)
    }
}
