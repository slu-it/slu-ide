package dev.sluide.gitlog

import dev.sluide.git.GitCommit
import dev.sluide.git.GitLogPage
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/** What the commit list shows when it has no rows, or a failure above kept rows. */
internal enum class GitLogState(
    val message: String,
) {
    /** Nothing asked for yet. */
    IDLE(""),
    LOADING("Loading…"),
    LOADED(""),
    EMPTY("No commits yet."),
    FAILED("The git log could not be read."),
}

/**
 * Which commits the git log shows and when they are loaded (ADR 0035), without JavaFX controls: nothing is loaded
 * until the log is [visible]; then a first page of [pageSize] commits, and the next page whenever a row within
 * [prefetch] rows of the last loaded one is [shown], until a page says it is complete.
 *
 * [load] runs on [background], its results are handled through [dispatch]. Every [open] and reload starts a new run;
 * results of older runs are dropped. Used on the JavaFX thread (or whatever [dispatch] stands for in tests).
 */
internal class GitLogPages(
    private val load: (Path, Int, Int) -> GitLogPage?,
    private val background: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
    private val pageSize: Int = PAGE_SIZE,
    private val prefetch: Int = PREFETCH,
) {
    /** The loaded commits, newest first. */
    val rows: ObservableList<GitCommit> = FXCollections.observableArrayList()

    private val _state = ReadOnlyObjectWrapper(GitLogState.IDLE)
    val state: ReadOnlyObjectProperty<GitLogState> = _state.readOnlyProperty

    /** The hashes of the selected rows; kept selected by [select] when a reload still has them. */
    var selectedHashes: () -> Collection<String> = { emptyList() }

    /** Selects the rows at the given indices, called after a reload replaced the rows. */
    var select: (List<Int>) -> Unit = {}

    private var root: Path? = null

    /** The current run; read on the background thread to skip runs that are no longer wanted. */
    private val run = AtomicLong()
    private var stale = false
    private var complete = false
    private var loadingMore = false

    /** Whether the log is shown; showing it loads the first page if nothing (or something outdated) is loaded. */
    var visible = false
        set(value) {
            field = value
            if (value && stale) reload()
        }

    /** Shows the log of the repository at [root]; `null` shows nothing. Loads only once [visible]. */
    fun open(root: Path?) {
        this.root = root
        run.incrementAndGet()
        rows.clear()
        complete = false
        loadingMore = false
        _state.set(GitLogState.IDLE)
        stale = root != null
        if (visible && stale) reload()
    }

    /** Loads the first page again now if [visible], else when it is shown next. */
    fun refresh() {
        if (root == null) return
        if (visible) reload() else stale = true
    }

    /** Loads the next page if the row at [index] is near the last loaded one and there may be more. */
    fun shown(index: Int) {
        if (_state.get() != GitLogState.LOADED || complete || loadingMore || index < rows.size - prefetch) return
        val root = root ?: return
        loadingMore = true
        val skip = rows.size
        start(root, skip) { page ->
            loadingMore = false
            if (page == null) {
                _state.set(GitLogState.FAILED)
            } else {
                rows.addAll(page.commits)
                complete = page.complete
            }
        }
    }

    /** Drops the results of runs still going. */
    fun close() {
        root = null
        run.incrementAndGet()
    }

    private fun reload() {
        val root = root ?: return
        stale = false
        loadingMore = false
        _state.set(GitLogState.LOADING)
        run.incrementAndGet()
        start(root, 0) { page ->
            if (page == null) {
                // Keeps the rows there are; no more pages until the next reload.
                _state.set(GitLogState.FAILED)
                return@start
            }
            val keep = selectedHashes().toSet()
            rows.setAll(page.commits)
            complete = page.complete
            _state.set(if (rows.isEmpty()) GitLogState.EMPTY else GitLogState.LOADED)
            if (keep.isNotEmpty()) select(rows.indices.filter { rows[it].hash in keep })
        }
    }

    private fun start(
        root: Path,
        skip: Int,
        handle: (GitLogPage?) -> Unit,
    ) {
        val current = run.get()
        background {
            if (run.get() != current) return@background
            val page = load(root, skip, pageSize)
            dispatch { if (run.get() == current) handle(page) }
        }
    }

    companion object {
        const val PAGE_SIZE = 500
        const val PREFETCH = 50
    }
}
