package dev.sluide.codereview.window

import dev.sluide.codereview.ResolvedReview
import dev.sluide.codereview.ReviewBase
import dev.sluide.codereview.ReviewCancellation
import dev.sluide.codereview.ReviewChange
import dev.sluide.codereview.ReviewCommit
import dev.sluide.codereview.ReviewException
import dev.sluide.codereview.ReviewFile
import dev.sluide.codereview.ReviewFileContent
import dev.sluide.codereview.ReviewRevision
import dev.sluide.codereview.ReviewScope
import dev.sluide.codereview.ReviewSource
import dev.sluide.codereview.model.ReviewDocument
import dev.sluide.codereview.model.ReviewLayout
import dev.sluide.codereview.model.ReviewRow
import dev.sluide.codereview.model.ReviewSelection
import dev.sluide.codereview.model.oneModifiedLine
import dev.sluide.codereview.model.shortRows
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal val ROOT = Path.of("/repo")
internal val DIRECT = Executor { it.run() }

internal fun commit(
    hash: String,
    parent: String?,
) = ReviewCommit(hash, hash, "subject $hash", "me", Instant.EPOCH, parent)

internal fun review(
    base: ReviewBase? = ReviewBase("refs/heads/main", "main", true),
    commits: List<ReviewCommit> = listOf(commit("c3", "c2"), commit("c2", "c1"), commit("c1", "c0")),
    title: String = "main..feature",
    uncommitted: Boolean = false,
) = ResolvedReview(title, base, "c0", "c3", commits, uncommitted)

internal class FakeSource(
    var files: List<ReviewFile> = emptyList(),
) : ReviewSource {
    var resolved: () -> ResolvedReview = { review() }
    var resolveHook: (ReviewCancellation) -> Unit = {}
    var fileDiffsHook: (List<ReviewFile>, Boolean, ReviewCancellation) -> Unit = { _, _, _ -> }
    var missing = emptySet<String>()

    /** The line count of every file's text. */
    var lines = 10
    var print: String? = "fp1"

    val scopes = CopyOnWriteArrayList<ReviewScope>()
    val ranges = CopyOnWriteArrayList<Pair<String?, ReviewRevision>>()
    val diffCalls = CopyOnWriteArrayList<Pair<List<String>, Boolean>>()
    val fingerprints = CopyOnWriteArrayList<ResolvedReview>()
    var fileCalls = 0

    var baseList = listOf(ReviewBase("refs/heads/main", "main", true))

    override fun bases(root: Path) = baseList

    override fun resolve(
        root: Path,
        scope: ReviewScope,
        cancellation: ReviewCancellation,
    ): ResolvedReview {
        scopes += scope
        resolveHook(cancellation)
        return resolved()
    }

    override fun changedFiles(
        root: Path,
        from: String?,
        to: ReviewRevision,
        cancellation: ReviewCancellation,
    ): List<ReviewFile> {
        ranges += from to to
        fileCalls++
        return files
    }

    override fun fileDiffs(
        root: Path,
        from: String?,
        to: ReviewRevision,
        files: List<ReviewFile>,
        ignoreWhitespace: Boolean,
        cancellation: ReviewCancellation,
    ): Map<String, ReviewFileContent> {
        diffCalls += files.map { it.path } to ignoreWhitespace
        fileDiffsHook(files, ignoreWhitespace, cancellation)
        return files.filter { it.path !in missing }.associate { it.path to oneModifiedLine(lines, 5) }
    }

    override fun fingerprint(
        root: Path,
        review: ResolvedReview,
    ): String? {
        fingerprints += review
        return print
    }
}

/** A viewport like the real list's: [firstVisible] is the place the last scroll asked for, or where the test puts it. */
internal class FakeViewport : ReviewViewport {
    var place = 0 to 0.0
    val scrolls = mutableListOf<Pair<Int, Double>>()

    override fun firstVisible() = place

    override fun scrollToRow(
        index: Int,
        offset: Double,
    ) {
        scrolls += index to offset
        place = index to offset
    }
}

internal fun file(path: String) = ReviewFile(ReviewChange.MODIFIED, path)

class ReviewLoaderTest {
    private val source = FakeSource()
    private val document = ReviewDocument()
    private val viewport = FakeViewport()

    private fun loader(
        executor: Executor = DIRECT,
        chunk: Int = ReviewLoader.CHUNK_SIZE,
    ) = ReviewLoader(source, ROOT, document, viewport, { _, _ -> null }, executor, { it() }, chunk)

    private fun ReviewLoader.openBranch(base: String? = null) = open(ReviewScope.Branch("HEAD", base))

    @Test
    fun `files are read in chunks of 25 in tree order`() {
        // Files are named so that tree order (folders first) differs from the order the source returns them in.
        source.files = (1..30).map { file("z%02d.txt".format(it)) } + (1..30).map { file("d/f%02d.txt".format(it)) }
        val loader = loader()

        loader.openBranch()

        assertEquals(3, source.diffCalls.size)
        assertEquals(listOf(25, 25, 10), source.diffCalls.map { it.first.size })
        assertEquals("d/f01.txt", source.diffCalls[0].first.first())
        assertEquals("d/f26.txt", source.diffCalls[1].first.first())
        assertEquals(
            source.files
                .map {
                    it.path
                }.sortedWith(compareBy({ !it.contains('/') }, { it })),
            source.diffCalls.flatMap { it.first },
        )
        assertFalse(loader.loading.get())
        assertNull(loader.message.get())
        assertEquals(60, document.files.size)
        assertFalse(document.shortRows().any { it.contains("LOADING") })
    }

    @Test
    fun `a branch scope fills the bases and resolves`() {
        source.files = listOf(file("a.kt"))
        val loader = loader()

        loader.openBranch()

        assertEquals(listOf(ReviewScope.Branch("HEAD")), source.scopes.toList())
        assertEquals(listOf("main"), loader.bases.map { it.label })
        assertEquals("main..feature", loader.resolved.get()?.title)
    }

    @Test
    fun `a second load cancels the first`() {
        source.files = listOf(file("a.kt"))
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        var first = true
        source.fileDiffsHook = { _, _, cancellation ->
            if (first) {
                first = false
                cancellation.onCancel { cancelled.countDown() }
                started.countDown()
                assertTrue(cancelled.await(10, TimeUnit.SECONDS), "the first load is cancelled")
                throw CancellationException()
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val loader = loader(executor)
            loader.openBranch()
            assertTrue(started.await(10, TimeUnit.SECONDS))

            loader.changeBase("refs/heads/other")
            executor.submit {}.get(10, TimeUnit.SECONDS)

            assertEquals(
                listOf(ReviewScope.Branch("HEAD"), ReviewScope.Branch("HEAD", "refs/heads/other")),
                source.scopes.toList(),
            )
            assertFalse(loader.loading.get())
            assertNull(loader.message.get(), "a cancelled load shows no error")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `hiding whitespace reads only the diffs again with the flag`() {
        source.files = listOf(file("a.kt"), file("b.kt"))
        val loader = loader()
        loader.openBranch()

        document.ignoreWhitespace.set(true)

        assertEquals(1, source.scopes.size, "no new resolve")
        assertEquals(1, source.fileCalls, "no new changedFiles")
        assertEquals(listOf(listOf("a.kt", "b.kt") to false, listOf("a.kt", "b.kt") to true), source.diffCalls.toList())
        assertFalse(document.shortRows().any { it.contains("LOADING") })
    }

    @Test
    fun `a selection loads that range`() {
        source.files = listOf(file("a.kt"))
        val loader = loader()
        loader.openBranch()

        loader.selection.set(ReviewSelection.Commits(0, 1))

        assertEquals("c0" to ReviewRevision.Commit("c3"), source.ranges.first())
        assertEquals("c1" to ReviewRevision.Commit("c3"), source.ranges.last())
        assertEquals(1, source.scopes.size, "no resolve for a selection")
    }

    @Test
    fun `changing the base resolves the branch with it`() {
        val loader = loader()
        loader.openBranch()

        loader.changeBase("refs/remotes/origin/dev")

        assertEquals(ReviewScope.Branch("HEAD", "refs/remotes/origin/dev"), source.scopes.last())
        assertEquals(ReviewSelection.All, loader.selection.get())
    }

    @Test
    fun `a file the source does not return is shown as unreadable`() {
        source.files = listOf(file("a.kt"), file("b.kt"))
        source.missing = setOf("b.kt")

        loader().openBranch()

        assertTrue(document.shortRows().contains("N UNREADABLE"))
        assertEquals(1, document.shortRows().count { it.contains("UNREADABLE") })
    }

    @Test
    fun `a review exception is shown, the cancellation of the load is not, any other is`() {
        val loader = loader()
        source.resolved = { throw ReviewException("bad ref") }
        loader.openBranch()
        assertEquals("Could not read the review: bad ref", loader.message.get())
        assertFalse(loader.loading.get())

        source.resolveHook = {
            it.cancel()
            throw CancellationException()
        }
        val cancelled = loader()
        cancelled.openBranch()
        assertNull(cancelled.message.get())

        // Nobody cancelled this load: the exception is a failure like any other.
        source.resolveHook = { throw CancellationException("stray") }
        val stray = loader()
        stray.openBranch()
        assertEquals("Could not read the review: stray", stray.message.get())
    }

    @Test
    fun `another failure is shown with its message`() {
        source.resolved = { error("boom") }
        val loader = loader()

        loader.openBranch()

        assertEquals("Could not read the review: boom", loader.message.get())
    }

    @Test
    fun `no files say so with the scope title`() {
        val loader = loader()

        loader.openBranch()

        assertEquals("No changes in main..feature.", loader.message.get())
    }

    @Test
    fun `a branch without a base loads nothing`() {
        source.resolved = { review(base = null) }
        source.files = listOf(file("a.kt"))
        val loader = loader()

        loader.openBranch()

        assertEquals("No default branch found; choose a base.", loader.message.get())
        assertEquals(0, source.fileCalls)
        assertTrue(document.files.isEmpty())
        assertFalse(loader.loading.get())
    }

    @Test
    fun `a commit scope without a base is loaded`() {
        source.resolved = { review(base = null) }
        source.files = listOf(file("a.kt"))
        val loader = loader()

        loader.open(ReviewScope.Commits("c1", "c3"))

        assertEquals(1, source.fileCalls)
        assertNull(loader.message.get())
    }

    @Test
    fun `a changed fingerprint makes the review stale, an equal one does not`() {
        source.files = listOf(file("a.kt"))
        val loader = loader()
        loader.openBranch()

        loader.checkStale()
        assertFalse(loader.stale.get())

        source.print = "fp2"
        loader.checkStale()
        assertTrue(loader.stale.get())
    }

    @Test
    fun `the fingerprint is not checked while loading`() {
        source.files = listOf(file("a.kt"))
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        source.fileDiffsHook = { _, _, _ ->
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val loader = loader(executor)
            loader.openBranch()
            assertTrue(started.await(10, TimeUnit.SECONDS), "the load is in its diffs")

            source.print = "other"
            loader.checkStale()
            release.countDown()
            executor.submit {}.get(10, TimeUnit.SECONDS)

            assertFalse(loader.stale.get())
            assertEquals(1, source.fingerprints.size, "only the load's own")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `reload keeps the selected commits, and the file at the top`() {
        source.files = listOf(file("a.kt"), file("b.kt"))
        val loader = loader()
        loader.openBranch()
        loader.selection.set(ReviewSelection.Commits(0, 1))
        // The first visible row is the header of b.kt.
        viewport.place = document.headerRow("b.kt") to 7.0
        source.resolved = { review(commits = listOf(commit("c4", "c3"), commit("c3", "c2"), commit("c2", "c1"))) }

        loader.reload()

        assertEquals(ReviewSelection.Commits(1, 2), loader.selection.get())
        // To the header while the content is not there, then to the anchor with the offset.
        assertEquals(document.headerRow("b.kt") to 7.0, viewport.scrolls.last())
        assertEquals(2, source.scopes.size)
    }

    @Test
    fun `reload selects all when the selected commits are gone`() {
        source.files = listOf(file("a.kt"))
        val loader = loader()
        loader.openBranch()
        loader.selection.set(ReviewSelection.Commits(0, 0))
        source.resolved = { review(commits = listOf(commit("x", "c0"))) }

        loader.reload()

        assertEquals(ReviewSelection.All, loader.selection.get())
    }

    @Test
    fun `reload scrolls straight to the line when the file's content was kept`() {
        source.files = listOf(file("a.kt"), file("b.kt"))
        val loader = loader()
        loader.openBranch()
        // A line row of b.kt is at the top.
        val line = document.rows.indexOfFirst { it.path == "b.kt" && it is ReviewRow.SplitLine }
        viewport.place = line to 3.0
        val anchor = document.anchorAt(line)

        loader.reload()

        assertEquals(listOf(document.rowOf(anchor) to 3.0), viewport.scrolls, "one scroll, no stop at the header")
    }

    @Test
    fun `keeping the place puts the first visible line back after a layout change`() {
        source.files = listOf(file("a.kt"))
        val loader = loader()
        loader.openBranch()
        val row = document.rows.size - 3
        viewport.place = row to 4.0
        val anchor = document.anchorAt(row)

        loader.keepingPlace { document.layout.set(ReviewLayout.UNIFIED) }

        assertEquals(document.rowOf(anchor) to 4.0, viewport.scrolls.last())
    }
}
