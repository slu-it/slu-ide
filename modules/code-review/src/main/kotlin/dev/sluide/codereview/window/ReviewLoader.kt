package dev.sluide.codereview.window

import dev.sluide.codereview.ResolvedReview
import dev.sluide.codereview.ReviewBase
import dev.sluide.codereview.ReviewCancellation
import dev.sluide.codereview.ReviewChange
import dev.sluide.codereview.ReviewException
import dev.sluide.codereview.ReviewFile
import dev.sluide.codereview.ReviewFileContent
import dev.sluide.codereview.ReviewRevision
import dev.sluide.codereview.ReviewScope
import dev.sluide.codereview.ReviewSource
import dev.sluide.codereview.model.PreparedFile
import dev.sluide.codereview.model.ReviewAnchor
import dev.sluide.codereview.model.ReviewDocument
import dev.sluide.codereview.model.ReviewSelection
import dev.sluide.codereview.model.prepareFile
import dev.sluide.codereview.model.range
import dev.sluide.codereview.model.treeOrder
import dev.sluide.fileviewers.SyntaxHighlighter
import javafx.beans.property.ObjectProperty
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.ReadOnlyStringProperty
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor

/** The scroll position of the diff list, as far as the loader needs it. */
internal interface ReviewViewport {
    /** The first visible row and how far its top is below the viewport's top. */
    fun firstVisible(): Pair<Int, Double>

    /** Scrolls so that row [index] starts [offset] pixels below the viewport's top. */
    fun scrollToRow(
        index: Int,
        offset: Double,
    )
}

/**
 * Loads one review into a [ReviewDocument] (ADR 0168): resolves the scope, reads the changed files, then the diffs of
 * the files in chunks, each chunk prepared (lines, highlighting, word diff) on the review thread by [executor] and
 * posted to the FX thread through [dispatch]. A new load (reload, selection, base, whitespace) cancels the running
 * one first; a cancelled load posts nothing. The properties and the document are for the FX thread; the methods are
 * called on it. FX-free apart from [dispatch].
 */
internal class ReviewLoader(
    private val source: ReviewSource,
    private val root: Path,
    private val document: ReviewDocument,
    private val viewport: ReviewViewport,
    private val highlighterFor: (Path, String) -> SyntaxHighlighter?,
    private val executor: Executor,
    private val dispatch: (() -> Unit) -> Unit,
    private val chunkSize: Int = CHUNK_SIZE,
) {
    private val resolvedProperty = ReadOnlyObjectWrapper<ResolvedReview?>(null)
    private val messageProperty = ReadOnlyStringWrapper(null)
    private val loadingProperty = ReadOnlyBooleanWrapper(false)
    private val staleProperty = ReadOnlyBooleanWrapper(false)

    /** The resolved scope; `null` until the first one is there. */
    val resolved: ReadOnlyObjectProperty<ResolvedReview?> = resolvedProperty.readOnlyProperty

    /** The bases a branch review can pick, filled with each resolve of a branch scope. */
    val bases: ObservableList<ReviewBase> = FXCollections.observableArrayList()

    /** The picked commits; setting it loads that range. Reset to [ReviewSelection.All] on each resolve. */
    val selection: ObjectProperty<ReviewSelection> = SimpleObjectProperty(ReviewSelection.All)

    /** What replaces the diff (an empty or failed review); `null` while there are files to show. */
    val message: ReadOnlyStringProperty = messageProperty.readOnlyProperty

    /** Whether a load is running. */
    val loading: ReadOnlyBooleanProperty = loadingProperty.readOnlyProperty

    /** Whether the reviewed changes changed since the last load; never reloads by itself. */
    val stale: ReadOnlyBooleanProperty = staleProperty.readOnlyProperty

    private var scope: ReviewScope? = null
    private var cancellation: ReviewCancellation? = null
    private var loads = 0

    /** What the review looked like when it was last loaded; `null` while a full load runs or after an error. */
    private var fingerprint: String? = null

    /** The fingerprint taken right after the running load resolved, published when a load finishes. */
    private var pendingFingerprint: String? = null

    /** Restarts the running load's first step (resolve, or reading the files) with the current settings. */
    private var firstStep: (() -> Unit)? = null

    /** The selection the user picked while a resolve ran; wins over the restored one when it fits the review. */
    private var pickedMeanwhile: ReviewSelection? = null
    private var settingSelection = false
    private var closed = false
    private var scrollRestore: ScrollRestore? = null

    init {
        selection.addListener { _, _, _ -> if (!settingSelection) selectionChanged() }
        document.ignoreWhitespace.addListener { _, _, _ -> whitespaceChanged() }
    }

    /** Resolves [scope] and shows it. */
    fun open(scope: ReviewScope) {
        this.scope = scope
        pickedMeanwhile = null
        resolve(null)
    }

    /** Resolves `Branch(head, ref)` again; only for a branch scope. */
    fun changeBase(ref: String) {
        val branch = scope as? ReviewScope.Branch ?: return
        scope = branch.copy(base = ref)
        pickedMeanwhile = null
        resolve(null)
    }

    /**
     * Resolves again and keeps the place: the selection if its commits are still there, and the file and line at the
     * top of the diff, or that file's header.
     */
    fun reload() {
        val place = currentAnchor()
        pickedMeanwhile = null
        resolve(Restore(selectedCommits(), place?.first, place?.second ?: 0.0))
    }

    /** Computes the fingerprint again and marks the review stale when it differs; not while loading. */
    fun checkStale() {
        if (closed || loading.get() || fingerprint == null) return
        val review = resolved.get() ?: return
        val generation = loads
        executor.execute {
            val now = source.fingerprint(root, review)
            dispatch {
                val unchanged = closed || generation != loads || loading.get() || now == null || now == fingerprint
                if (!unchanged) staleProperty.set(true)
            }
        }
    }

    /** Runs [change] (a layout switch) and puts the diff back where it was: same row anchor at the same offset. */
    fun keepingPlace(change: () -> Unit) {
        val place = currentAnchor()
        change()
        if (place == null) return
        val row = document.rowOf(place.first)
        if (row >= 0) viewport.scrollToRow(row, place.second)
    }

    /** Cancels the running load and ignores everything still posted. */
    fun close() {
        closed = true
        cancelRunning()
    }

    /** A selection made while the first step runs: the whole step starts again with it. */
    private fun selectionChanged() {
        val restart = firstStep
        if (restart == null) {
            loadSelection()
        } else {
            pickedMeanwhile = selection.get()
            restart()
        }
    }

    /** The whitespace flag changed: before the file list is in the document, the whole first step starts again. */
    private fun whitespaceChanged() {
        val restart = firstStep
        if (restart == null) reloadDiffs() else restart()
    }

    private fun resolve(restore: Restore?) {
        val scope = scope ?: return
        val whitespace = document.ignoreWhitespace.get()
        val meanwhile = pickedMeanwhile
        val cancellation = begin(full = true)
        firstStep = { resolve(restore) }
        executor.execute {
            guarded(cancellation) {
                val review = source.resolve(root, scope, cancellation)
                val bases = if (scope is ReviewScope.Branch) source.bases(root) else null
                // Before the files are read: what changes while they are read makes the review stale afterwards.
                val print = source.fingerprint(root, review)
                val picked =
                    meanwhile?.takeIf { it.fits(review) } ?: restore?.selectionIn(review) ?: ReviewSelection.All
                dispatch {
                    if (!cancellation.isCancelled) {
                        resolvedProperty.set(review)
                        bases?.let(this.bases::setAll)
                        settingSelection = true
                        try {
                            selection.set(picked)
                        } finally {
                            settingSelection = false
                        }
                        messageProperty.set(null)
                        pendingFingerprint = print
                    }
                }
                if (scope is ReviewScope.Branch && review.base == null) {
                    dispatch {
                        if (!cancellation.isCancelled) {
                            firstStep = null
                            pickedMeanwhile = null
                            document.setFiles(emptyList())
                            messageProperty.set(NO_BASE)
                            finish()
                        }
                    }
                } else {
                    loadFiles(review, picked, restore, whitespace, cancellation)
                }
            }
        }
    }

    private fun loadSelection() {
        val review = resolved.get() ?: return
        val whitespace = document.ignoreWhitespace.get()
        val picked = selection.get()
        val cancellation = begin(full = false)
        firstStep = { loadSelection() }
        executor.execute { guarded(cancellation) { loadFiles(review, picked, null, whitespace, cancellation) } }
    }

    /** The whitespace flag changed with the files in the document, which dropped the contents: read the diffs again. */
    private fun reloadDiffs() {
        val review = resolved.get() ?: return
        val whitespace = document.ignoreWhitespace.get()
        val picked = selection.get()
        val files = document.files
        val cancellation = begin(full = false)
        executor.execute {
            guarded(cancellation) {
                val (from, to) = review.range(picked)
                loadDiffs(from, to, files, whitespace, cancellation)
            }
        }
    }

    /** Reads the changed files of [picked], posts them, then loads their diffs. Runs on the review thread. */
    private fun loadFiles(
        review: ResolvedReview,
        picked: ReviewSelection,
        restore: Restore?,
        whitespace: Boolean,
        cancellation: ReviewCancellation,
    ) {
        val (from, to) = review.range(picked)
        val files = treeOrder(source.changedFiles(root, from, to, cancellation))
        dispatch {
            if (!cancellation.isCancelled) {
                firstStep = null
                pickedMeanwhile = null
                document.setFiles(files)
                messageProperty.set(if (files.isEmpty()) "No changes in ${review.title}." else null)
                restore?.anchor?.let { restoreScroll(it, restore.offset) }
            }
        }
        loadDiffs(from, to, files, whitespace, cancellation)
    }

    private fun loadDiffs(
        from: String?,
        to: ReviewRevision,
        files: List<ReviewFile>,
        whitespace: Boolean,
        cancellation: ReviewCancellation,
    ) {
        for (chunk in files.chunked(chunkSize)) {
            if (cancellation.isCancelled) return
            val contents = source.fileDiffs(root, from, to, chunk, whitespace, cancellation)
            val prepared =
                chunk.map { file ->
                    val content = contents[file.path]
                    file.path to content?.let { prepareFile(file, it, highlighterOf(file, it), whitespace) }
                }
            dispatch { if (!cancellation.isCancelled) showChunk(prepared) }
        }
        dispatch {
            if (!cancellation.isCancelled) {
                scrollRestore = null
                finish()
            }
        }
    }

    /** Sets the contents of a chunk, and scrolls to the restored line when its file is in it and nobody scrolled. */
    private fun showChunk(prepared: List<Pair<String, PreparedFile?>>) {
        val restore = scrollRestore?.takeIf { restore -> prepared.any { it.first == restore.anchor.path } }
        val unmoved = restore != null && !restore.moved()
        for ((path, file) in prepared) {
            if (file == null) document.setUnreadable(path) else document.setContent(path, file)
        }
        if (restore != null) {
            scrollRestore = null
            if (unmoved) scrollToAnchor(restore)
        }
    }

    private fun highlighterOf(
        file: ReviewFile,
        content: ReviewFileContent,
    ): SyntaxHighlighter? {
        val text = content as? ReviewFileContent.Text ?: return null
        return try {
            highlighterFor(Path.of(file.path), if (file.change == ReviewChange.DELETED) text.original else text.current)
        } catch (_: InvalidPathException) {
            null
        }
    }

    /** Runs a load on the review thread: a failure becomes the message, a cancellation of this load nothing. */
    private fun guarded(
        cancellation: ReviewCancellation,
        load: () -> Unit,
    ) {
        try {
            if (!cancellation.isCancelled) load()
        } catch (e: ReviewException) {
            fail(cancellation, e.message)
        } catch (e: Exception) {
            // A CancellationException of a load that was not cancelled is a failure like any other.
            if (e is CancellationException && cancellation.isCancelled) return
            log.log(System.Logger.Level.ERROR, "Reading the review failed", e)
            fail(cancellation, e.message ?: e.toString())
        }
    }

    private fun fail(
        cancellation: ReviewCancellation,
        reason: String?,
    ) {
        dispatch {
            if (!cancellation.isCancelled) {
                messageProperty.set("Could not read the review: ${reason.orEmpty()}")
                pendingFingerprint = null
                loadingProperty.set(false)
            }
        }
    }

    /** A load ran to its end: what the review looked like at its resolve is now what the window shows. */
    private fun finish() {
        pendingFingerprint?.let { fingerprint = it }
        pendingFingerprint = null
        loadingProperty.set(false)
    }

    /**
     * Cancels the running load and starts the bookkeeping of the next one. A [full] load (resolve) forgets the
     * fingerprint and the stale notice; a partial one (selection, whitespace) keeps both.
     */
    private fun begin(full: Boolean): ReviewCancellation {
        cancelRunning()
        val next = ReviewCancellation()
        cancellation = next
        loads++
        scrollRestore = null
        firstStep = null
        loadingProperty.set(true)
        if (full) {
            staleProperty.set(false)
            fingerprint = null
            pendingFingerprint = null
        }
        return next
    }

    private fun cancelRunning() {
        try {
            cancellation?.cancel()
        } catch (e: Exception) {
            log.log(System.Logger.Level.WARNING, "Cancelling the load failed", e)
        }
    }

    /** The anchor of the first visible row and its offset; `null` without rows. */
    private fun currentAnchor(): Pair<ReviewAnchor, Double>? {
        val (row, offset) = viewport.firstVisible()
        return if (row in document.rows.indices) document.anchorAt(row) to offset else null
    }

    /**
     * Puts the diff back at [anchor] after a reload. The line is there when the file's content was kept: scroll to
     * it and be done. Else scroll to the file's header and wait for the content ([showChunk]); a path that is gone
     * is not scrolled to.
     */
    private fun restoreScroll(
        anchor: ReviewAnchor,
        offset: Double,
    ) {
        val row = document.rowOf(anchor)
        if (row < 0) return
        if (document.anchorAt(row) == anchor) {
            viewport.scrollToRow(row, offset)
        } else {
            viewport.scrollToRow(row, 0.0)
            scrollRestore = ScrollRestore(anchor, offset, ReviewAnchor(anchor.path, null, null) to 0.0)
        }
    }

    private fun scrollToAnchor(restore: ScrollRestore) {
        val row = document.rowOf(restore.anchor)
        if (row >= 0) viewport.scrollToRow(row, restore.offset)
    }

    /** The commits of the current selection, by hash; for a reload to find them again. */
    private fun selectedCommits(): PickedCommits {
        val commits = resolved.get()?.commits.orEmpty()
        return when (val picked = selection.get()) {
            ReviewSelection.All -> {
                PickedCommits(null, false)
            }

            ReviewSelection.Uncommitted -> {
                PickedCommits(null, true)
            }

            is ReviewSelection.Commits -> {
                val newest = commits.getOrNull(picked.newest)
                val oldest = commits.getOrNull(picked.oldest)
                PickedCommits(if (newest != null && oldest != null) newest.hash to oldest.hash else null, false)
            }
        }
    }

    private fun ReviewSelection.fits(review: ResolvedReview): Boolean =
        when (this) {
            ReviewSelection.All -> true
            ReviewSelection.Uncommitted -> review.uncommitted
            is ReviewSelection.Commits -> newest in 0..oldest && oldest < review.commits.size
        }

    /** The picked commits of a review: newest and oldest hash of a range, or the uncommitted changes. */
    private class PickedCommits(
        val hashes: Pair<String, String>?,
        val uncommitted: Boolean,
    )

    /** What a reload keeps: the selected commits, and the place in the diff. */
    private class Restore(
        val picked: PickedCommits,
        val anchor: ReviewAnchor?,
        val offset: Double,
    ) {
        /** The selection again, when its commits are still in [review]; else All. */
        fun selectionIn(review: ResolvedReview): ReviewSelection {
            if (picked.uncommitted) return if (review.uncommitted) ReviewSelection.Uncommitted else ReviewSelection.All
            val (newest, oldest) = picked.hashes ?: return ReviewSelection.All
            val newestIndex = review.commits.indexOfFirst { it.hash == newest }
            val oldestIndex = review.commits.indexOfFirst { it.hash == oldest }
            return if (newestIndex in 0..oldestIndex) {
                ReviewSelection.Commits(newestIndex, oldestIndex)
            } else {
                ReviewSelection.All
            }
        }
    }

    /**
     * A scroll towards [anchor] that waits for the file's content. The list applies a scroll at its next layout, so
     * the loader never reads the place back: [expected] is what it asked for. The user has not scrolled while the
     * list still stands where it did before the reload ([before]) or at [expected].
     */
    private inner class ScrollRestore(
        val anchor: ReviewAnchor,
        val offset: Double,
        val expected: Pair<ReviewAnchor, Double>,
    ) {
        val before: Pair<ReviewAnchor, Double> = anchor to offset

        fun moved(): Boolean = currentAnchor().let { it != expected && it != before }
    }

    companion object {
        /** Files per `fileDiffs` call. */
        const val CHUNK_SIZE = 25

        const val NO_BASE = "No default branch found; choose a base."

        private val log = System.getLogger(ReviewLoader::class.java.name)
    }
}
