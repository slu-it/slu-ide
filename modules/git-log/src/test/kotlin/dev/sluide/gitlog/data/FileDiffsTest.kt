package dev.sluide.gitlog.data

import dev.sluide.git.GitCancellation
import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.git.GitFileDiff
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileDiffsTest {
    private val repo = Path.of("/repo")
    private val background = ArrayDeque<Runnable>()
    private val dispatched = ArrayDeque<Runnable>()
    private val loads = mutableListOf<String>()

    /** The cancellation of every load, which stops its git. */
    private val cancellations = mutableListOf<GitCancellation>()

    /** Runs while a diff is read, e.g. to ask for another one meanwhile. */
    private var whileLoading: () -> Unit = {}
    private val diffs =
        FileDiffs(
            { _, commit, parent, file, cancellation ->
                loads += "$commit:$parent:${file.path}"
                cancellations += cancellation
                whileLoading()
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
    fun `a newer request or closing stops the git of the diff being read`() {
        whileLoading = { if (loads.size == 1) request("b.txt") }
        request("a.txt")
        settle()
        assertEquals(listOf(true, false), cancellations.map { it.cancelled })
        assertEquals(listOf<GitFileDiff?>(GitFileDiff.Text("", "b.txt", emptyList())), handled)

        whileLoading = { diffs.close() }
        request("c.txt")
        settle()
        assertEquals(3, cancellations.size)
        assertTrue(cancellations.last().cancelled, "closed while it read")
    }

    @Test
    fun `nothing is handled after close`() {
        request("a.txt")
        diffs.close()
        settle()

        assertEquals(emptyList(), handled)
    }
}
