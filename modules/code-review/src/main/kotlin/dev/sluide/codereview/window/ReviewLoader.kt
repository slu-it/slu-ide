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
    private var fingerprint: String? = null
    private var settingSelection = false
    private var closed = false
    private var scrollRestore: ScrollRestore? = null

    init {
        selection.addListener { _, _, _ -> if (!settingSelection) loadSelection() }
        document.ignoreWhitespace.addListener { _, _, _ -> reloadDiffs() }
    }

    /** Resolves [scope] and shows it. */
    fun open(scope: ReviewScope) {
        this.scope = scope
        resolve(null)
    }

    /** Resolves `Branch(head, ref)` again; only for a branch scope. */
    fun changeBase(ref: String) {
        val branch = scope as? ReviewScope.Branch ?: return
        scope = branch.copy(base = ref)
        resolve(null)
    }

    /**
     * Resolves again and keeps the place: the selection if its commits are still there, and the file and line at the
     * top of the diff, or that file's header.
     */
    fun reload() {
        val place = currentAnchor()
        resolve(Restore(selectedHashes(), place?.first, place?.second ?: 0.0))
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

    private fun resolve(restore: Restore?) {
        val scope = scope ?: return
        val whitespace = document.ignoreWhitespace.get()
        val cancellation = begin()
        executor.execute {
            guarded(cancellation) {
                val review = source.resolve(root, scope, cancellation)
                val bases = if (scope is ReviewScope.Branch) source.bases(root) else null
                val picked = restore?.selectionIn(review) ?: ReviewSelection.All
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
                    }
                }
                if (scope is ReviewScope.Branch && review.base == null) {
                    dispatch {
                        if (!cancellation.isCancelled) {
                            document.setFiles(emptyList())
                            messageProperty.set(NO_BASE)
                            loadingProperty.set(false)
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
        val cancellation = begin()
        executor.execute { guarded(cancellation) { loadFiles(review, picked, null, whitespace, cancellation) } }
    }

    /** The whitespace flag changed: the document dropped the contents; read only the diffs again. */
    private fun reloadDiffs() {
        val review = resolved.get() ?: return
        val whitespace = document.ignoreWhitespace.get()
        val picked = selection.get()
        val files = document.files
        val cancellation = begin()
        executor.execute {
            guarded(cancellation) {
                val (from, to) = review.range(picked)
                loadDiffs(review, from, to, files, whitespace, cancellation)
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
                document.setFiles(files)
                messageProperty.set(if (files.isEmpty()) "No changes in ${review.title}." else null)
                scrollRestore = restore?.anchor?.let { ScrollRestore(it, restore.offset) }
                scrollRestore?.let(::scrollToHeader)
            }
        }
        loadDiffs(review, from, to, files, whitespace, cancellation)
    }

    private fun loadDiffs(
        review: ResolvedReview,
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
        val fingerprint = source.fingerprint(root, review)
        dispatch {
            if (!cancellation.isCancelled) {
                this.fingerprint = fingerprint
                scrollRestore = null
                loadingProperty.set(false)
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

    /** Runs a load on the review thread: a failure becomes the message, a cancellation nothing. */
    private fun guarded(
        cancellation: ReviewCancellation,
        load: () -> Unit,
    ) {
        try {
            if (!cancellation.isCancelled) load()
        } catch (_: CancellationException) {
            // A newer load or the window's closing cancelled this one.
        } catch (e: ReviewException) {
            fail(cancellation, e.message)
        } catch (e: Exception) {
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
                loadingProperty.set(false)
            }
        }
    }

    /** Cancels the running load and starts the bookkeeping of the next one. */
    private fun begin(): ReviewCancellation {
        cancelRunning()
        val next = ReviewCancellation()
        cancellation = next
        loads++
        scrollRestore = null
        loadingProperty.set(true)
        staleProperty.set(false)
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

    private fun scrollToHeader(restore: ScrollRestore) {
        val header = document.headerRow(restore.anchor.path)
        if (header < 0) {
            scrollRestore = null
            return
        }
        viewport.scrollToRow(header, 0.0)
        restore.set = currentAnchor()
    }

    private fun scrollToAnchor(restore: ScrollRestore) {
        val row = document.rowOf(restore.anchor)
        if (row >= 0) viewport.scrollToRow(row, restore.offset)
    }

    /** The commits of the current selection, newest and oldest hash; `null` for All and Uncommitted. */
    private fun selectedHashes(): Pair<String, String>? {
        val commits = resolved.get()?.commits ?: return null
        val picked = selection.get() as? ReviewSelection.Commits ?: return null
        val newest = commits.getOrNull(picked.newest) ?: return null
        val oldest = commits.getOrNull(picked.oldest) ?: return null
        return newest.hash to oldest.hash
    }

    /** What a reload keeps: the selected commits by hash, and the place in the diff. */
    private class Restore(
        val hashes: Pair<String, String>?,
        val anchor: ReviewAnchor?,
        val offset: Double,
    ) {
        /** The selection again, when its commits are still in [review]; else All. */
        fun selectionIn(review: ResolvedReview): ReviewSelection {
            val (newest, oldest) = hashes ?: return ReviewSelection.All
            val newestIndex = review.commits.indexOfFirst { it.hash == newest }
            val oldestIndex = review.commits.indexOfFirst { it.hash == oldest }
            return if (newestIndex in 0..oldestIndex) {
                ReviewSelection.Commits(newestIndex, oldestIndex)
            } else {
                ReviewSelection.All
            }
        }
    }

    /** A scroll the loader made towards [anchor]; [set] is where the list stood after it. */
    private inner class ScrollRestore(
        val anchor: ReviewAnchor,
        val offset: Double,
    ) {
        var set: Pair<ReviewAnchor, Double>? = null

        /** The user scrolled since: the list does not stand where the loader put it. */
        fun moved(): Boolean = currentAnchor() != set
    }

    companion object {
        /** Files per `fileDiffs` call. */
        const val CHUNK_SIZE = 25

        const val NO_BASE = "No default branch found; choose a base."

        private val log = System.getLogger(ReviewLoader::class.java.name)
    }
}
