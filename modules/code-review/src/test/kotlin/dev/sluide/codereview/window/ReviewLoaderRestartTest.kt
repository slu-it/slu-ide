package dev.sluide.codereview.window

import dev.sluide.codereview.ReviewException
import dev.sluide.codereview.ReviewRevision
import dev.sluide.codereview.ReviewScope
import dev.sluide.codereview.model.ReviewAnchor
import dev.sluide.codereview.model.ReviewDocument
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.codereview.model.ReviewSelection
import dev.sluide.codereview.model.document
import dev.sluide.codereview.model.oneModifiedLine
import dev.sluide.codereview.model.shortRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The posts to the FX thread, held back until the test lets them run. */
private class Posts {
    val tasks = ArrayDeque<() -> Unit>()

    fun post(task: () -> Unit) {
        tasks += task
    }

    fun step() = tasks.removeFirst()()

    fun drain() {
        while (tasks.isNotEmpty()) step()
    }
}

/**
 * Loads that are interrupted, and what a load leaves behind: the source runs at once (direct executor) but what it
 * found reaches the document only when the test drains the posts, so each test stops the load where it wants.
 * Posts of a cancelled load are in the queue too, like the ones already posted to the FX thread in the real window.
 */
class ReviewLoaderRestartTest {
    private val source = FakeSource(listOf(file("a.kt")))
    private val document = ReviewDocument()
    private val posts = Posts()

    private fun loader(viewport: ReviewViewport = FakeViewport()) =
        ReviewLoader(source, ROOT, document, viewport, { _, _ -> null }, DIRECT, posts::post)

    private fun ReviewLoader.openBranch() = open(ReviewScope.Branch("HEAD"))

    private fun noLoading() = assertFalse(document.shortRows().any { it.contains("LOADING") }, "contents are there")

    @Test
    fun `hiding whitespace before the first resolve arrived reads everything again with the flag`() {
        val loader = loader()
        loader.openBranch()

        document.ignoreWhitespace.set(true)
        posts.drain()

        assertEquals(listOf(listOf("a.kt") to false, listOf("a.kt") to true), source.diffCalls.toList())
        assertEquals(2, source.scopes.size)
        assertEquals(1, document.files.size)
        noLoading()
        assertFalse(loader.loading.get())
    }

    @Test
    fun `hiding whitespace after the resolve arrived but before the files did reads the files again`() {
        val loader = loader()
        loader.openBranch()
        posts.step() // the resolved review

        document.ignoreWhitespace.set(true)
        posts.drain()

        assertEquals(listOf("a.kt") to true, source.diffCalls.last())
        assertEquals(1, document.files.size)
        noLoading()
    }

    @Test
    fun `a selection made while the base is resolved again does not drop the base`() {
        val loader = loader()
        loader.openBranch()
        posts.drain()

        loader.changeBase("refs/heads/other")
        loader.selection.set(ReviewSelection.Commits(0, 1))
        posts.drain()

        assertEquals(ReviewScope.Branch("HEAD", "refs/heads/other"), source.scopes.last())
        assertEquals(3, source.scopes.size)
        assertEquals(ReviewSelection.Commits(0, 1), loader.selection.get())
        assertEquals("c1" to ReviewRevision.Commit("c3"), source.ranges.last())
        noLoading()
    }

    @Test
    fun `hiding whitespace with the files in the document reads only the diffs`() {
        val loader = loader()
        loader.openBranch()
        posts.drain()

        document.ignoreWhitespace.set(true)
        posts.drain()

        assertEquals(1, source.scopes.size)
        assertEquals(1, source.fileCalls)
        assertEquals(listOf("a.kt") to true, source.diffCalls.last())
    }

    @Test
    fun `what a cancelled load posted late reaches nothing`() {
        var resolves = 0
        source.resolved = { review(title = if (resolves++ == 0) "first" else "second") }
        source.resolveHook = { source.files = listOf(file(if (resolves == 0) "first.kt" else "second.kt")) }
        val loader = loader()
        val titles = mutableListOf<String?>()
        loader.resolved.addListener { _, _, review -> titles += review?.title }

        loader.openBranch()
        // The source of the first load returned normally; the second one starts before its posts run.
        loader.changeBase("refs/heads/other")
        posts.drain()

        assertEquals(listOf<String?>("second"), titles)
        assertEquals(listOf("second.kt"), document.files.map { it.path })
        assertTrue(document.shortRows().none { it.contains("first") })
    }

    @Test
    fun `the chunk and the end of a cancelled load, posted after the new load's, change nothing`() {
        val loader = loader()
        loader.openBranch()
        posts.drain()
        val expected = document("a.kt" to oneModifiedLine(10, 5)).shortRows()
        // The reload's file read starts a second load, which posts everything before the reload's chunk and end.
        var armed = true
        source.fileDiffsHook = { _, _, _ ->
            if (armed) {
                armed = false
                loader.changeBase("refs/heads/other")
                source.lines = 30
            }
        }

        loader.reload()
        while (posts.tasks.size > 1) posts.step()
        assertEquals(expected, document.shortRows(), "the content of the second load, not the cancelled one's")

        // A third load is running when the cancelled load's end comes: it must not end that load.
        loader.selection.set(ReviewSelection.Commits(0, 0))
        posts.step()
        assertTrue(loader.loading.get())

        posts.drain()
        assertFalse(loader.loading.get())
    }

    @Test
    fun `a selection made while the review is resolved again follows its commits, not their positions`() {
        var resolves = 0
        source.resolved = {
            if (resolves++ == 0) review() else review(commits = listOf(commit("c4", "c3")) + review().commits)
        }
        val loader = loader()
        loader.openBranch()
        posts.drain()

        loader.changeBase("refs/heads/other")
        loader.selection.set(ReviewSelection.Commits(1, 2)) // c2..c1 in the review the user sees
        posts.drain()

        assertEquals(ReviewSelection.Commits(2, 3), loader.selection.get())
        assertEquals("c0" to ReviewRevision.Commit("c2"), source.ranges.last())
    }

    @Test
    fun `a change while the files were read leaves the review stale afterwards`() {
        source.fileDiffsHook = { _, _, _ -> source.print = "later" }
        val loader = loader()
        loader.openBranch()
        posts.drain()

        loader.checkStale()
        posts.drain()

        assertTrue(loader.stale.get())
    }

    @Test
    fun `a selection keeps a pending notice, a reload takes it away`() {
        val loader = loader()
        loader.openBranch()
        posts.drain()
        source.print = "changed"
        loader.checkStale()
        posts.drain()
        assertTrue(loader.stale.get())

        loader.selection.set(ReviewSelection.Commits(0, 0))
        posts.drain()
        assertTrue(loader.stale.get(), "the same review is still shown")

        loader.reload()
        assertFalse(loader.stale.get())
    }

    @Test
    fun `after an error no change is reported until a load went through`() {
        val loader = loader()
        loader.openBranch()
        posts.drain()
        source.resolved = { throw ReviewException("broken") }
        loader.reload()
        posts.drain()
        val checked = source.fingerprints.size

        source.print = "changed"
        loader.checkStale()
        posts.drain()

        assertEquals("Could not read the review: broken", loader.message.get())
        assertFalse(loader.stale.get())
        assertEquals(checked, source.fingerprints.size, "nothing to compare with")
    }

    @Test
    fun `reload keeps the uncommitted changes while there are some`() {
        source.resolved = { review(uncommitted = true) }
        val loader = loader()
        loader.openBranch()
        posts.drain()
        loader.selection.set(ReviewSelection.Uncommitted)
        posts.drain()

        loader.reload()
        posts.drain()
        assertEquals(ReviewSelection.Uncommitted, loader.selection.get())

        source.resolved = { review(uncommitted = false) }
        loader.reload()
        posts.drain()
        assertEquals(ReviewSelection.All, loader.selection.get())
    }

    /** A reload whose first resolve was restarted by hiding whitespace: the content is dropped when the files land. */
    private fun reloadWithDroppedContent(viewport: FakeViewport): ReviewAnchor {
        source.files = listOf(file("a.kt"), file("b.kt"))
        val loader = loader(viewport)
        loader.openBranch()
        posts.drain()
        val line = document.rows.indexOfFirst { it.path == "b.kt" && it is ReviewRow.SplitLine }
        viewport.place = line to 3.0
        val anchor = document.anchorAt(line)

        loader.reload()
        posts.tasks.clear()
        document.ignoreWhitespace.set(true)
        posts.step() // the resolved review
        posts.step() // the files: the line is not there, the loader scrolls to the header
        assertEquals(document.headerRow("b.kt") to 0.0, viewport.scrolls.last())
        return anchor
    }

    @Test
    fun `reload scrolls to the line when its content arrives after a stop at the header`() {
        val viewport = FakeViewport()
        val anchor = reloadWithDroppedContent(viewport)
        assertEquals(document.headerRow("b.kt") to 0.0, viewport.place)

        posts.step() // the contents

        assertEquals(document.rowOf(anchor) to 3.0, viewport.scrolls.last())
        assertTrue(document.rowOf(anchor) > document.headerRow("b.kt"), "the line, not the header")
    }

    @Test
    fun `reload does not jump to the line when the user scrolled after the header`() {
        val viewport = FakeViewport()
        reloadWithDroppedContent(viewport)
        val scrolls = viewport.scrolls.size
        // The user scrolls to the top of the first file.
        viewport.place = 0 to 0.0

        posts.step() // the contents

        assertEquals(scrolls, viewport.scrolls.size, "no scroll to the line")
    }
}
