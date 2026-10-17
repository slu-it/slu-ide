package dev.sluide.gitlog

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitFileDiff
import dev.sluide.git.GitWorkingTreeChanges
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkingTreeComparisonTest {
    private val root = Path.of("/repo")
    private val file = GitChangedFile(GitChange.MODIFIED, "a.txt")

    /** What the next read of the files gives. */
    private var changes: GitWorkingTreeChanges? = GitWorkingTreeChanges.Files("c1", listOf(file))

    private val fileLoads = mutableListOf<Pair<Path, String>>()
    private val diffLoads = mutableListOf<Pair<String, String>>()
    private val background = ArrayDeque<Runnable>()
    private val dispatched = ArrayDeque<Runnable>()

    private val comparison =
        WorkingTreeComparison(
            WorkingTreeTarget(root, "refs/heads/feature", "feature"),
            { root, revision ->
                fileLoads += root to revision
                changes
            },
            { _, commit, file ->
                diffLoads += commit to file.path
                GitFileDiff.Text("old", "new", emptyList())
            },
            background::addLast,
            dispatched::addLast,
        )

    private fun settle() {
        while (background.isNotEmpty() || dispatched.isNotEmpty()) {
            while (background.isNotEmpty()) background.removeFirst().run()
            while (dispatched.isNotEmpty()) dispatched.removeFirst().run()
        }
    }

    @Test
    fun `reads the files of the revision, keeps them while a refresh runs, and tells a missing revision`() {
        assertEquals(WorkingTreeState.Loading, comparison.state.get())
        comparison.refresh()
        assertTrue(comparison.loading)
        settle()
        assertFalse(comparison.loading)
        assertEquals(WorkingTreeState.Loaded("c1", listOf(file)), comparison.state.get())
        assertEquals(listOf(root to "refs/heads/feature"), fileLoads)

        changes = GitWorkingTreeChanges.Files("c2", emptyList())
        comparison.refresh()
        assertEquals(WorkingTreeState.Loaded("c1", listOf(file)), comparison.state.get(), "kept while it reads")
        settle()
        assertEquals(WorkingTreeState.Loaded("c2", emptyList()), comparison.state.get())

        changes = GitWorkingTreeChanges.Missing
        comparison.refresh()
        settle()
        assertEquals(WorkingTreeState.Missing, comparison.state.get())

        changes = null
        comparison.refresh()
        settle()
        assertEquals(WorkingTreeState.Failed, comparison.state.get())
    }

    @Test
    fun `the latest refresh and diff win, a closed comparison tells nothing`() {
        comparison.refresh()
        changes = GitWorkingTreeChanges.Files("c2", listOf(file))
        comparison.refresh()
        settle()
        assertEquals(1, fileLoads.size, "the first one was superseded before it ran")
        assertEquals(WorkingTreeState.Loaded("c2", listOf(file)), comparison.state.get())

        val handled = mutableListOf<String>()
        comparison.diff(file) { commit, _ -> handled += "first $commit" }
        comparison.diff(file) { commit, diff -> handled += "second $commit ${(diff as GitFileDiff.Text).current}" }
        settle()
        assertEquals(listOf("second c2 new"), handled)
        assertEquals(listOf("c2" to "a.txt"), diffLoads)

        comparison.diff(file) { _, _ -> handled += "after close" }
        comparison.refresh()
        comparison.close()
        settle()
        assertEquals(listOf("second c2 new"), handled)
        comparison.refresh()
        settle()
        assertEquals(1, fileLoads.size, "nothing read once closed")
    }
}
