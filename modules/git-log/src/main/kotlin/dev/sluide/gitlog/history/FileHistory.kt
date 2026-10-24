package dev.sluide.gitlog.history

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.git.GitCancellation
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitFileHistoryPage
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.gitlog.data.GitLogPages
import dev.sluide.gitlog.data.GitLogRow
import dev.sluide.gitlog.data.GraphRow
import javafx.beans.property.ReadOnlyBooleanProperty
import javafx.beans.property.ReadOnlyBooleanWrapper
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Which history a tab of the git log area shows (ADR 0046): of the file [path] (relative to the repository's root,
 * `/`-separated) from the commit [start] on (a full hash), or from `HEAD` (`null`).
 */
internal data class FileHistoryKey(
    val path: String,
    val start: String? = null,
) {
    /** The file's name, the last segment of its path. */
    val name: String get() = path.substringAfterLast('/')
}

/** What the table of a file's history shows when it has no rows, or a failure above kept rows. */
internal enum class FileHistoryState {
    /** Nothing asked for yet. */
    IDLE,
    LOADING,
    LOADED,

    /** The file has no commits: untracked, never committed, or `HEAD` has none. */
    EMPTY,
    FAILED,

    /** A load was cancelled (ADR 0061); the rows there are stay until a refresh. */
    CANCELLED,
    ;

    /** What the table says in this state for the file [name]. */
    fun message(name: String): String =
        when (this) {
            LOADING -> "Loading…"
            EMPTY -> "No history: $name has no commits."
            FAILED -> "The history could not be read."
            CANCELLED -> "Loading cancelled. Refresh to load."
            IDLE, LOADED -> ""
        }

    /** Whether rows shown in this state are left from before, so the header says why ([message]). */
    val keepsRows: Boolean get() = this == FAILED || this == CANCELLED
}

/**
 * The commits of a file's history and when they are loaded (ADR 0046), without JavaFX controls: nothing is loaded
 * until it is [visible]; then a first page of [pageSize] commits, and the next page whenever a row within [prefetch]
 * rows of the last loaded one is [shown], until a page says it is complete. The rows have no graph and the refs read
 * with [loadRefs] right after each first page; [fileOf] gives the file's change at the commit of a row. The first load
 * selects the row of the [FileHistoryKey.start] commit, else the first; a [refresh] reads as many rows as are loaded
 * again and keeps the selected commit by hash if it is still there.
 *
 * [load] and [loadRefs] run on [background] (a thread of the history's own, not the log's: a history's git may run for
 * long), their results are handled through [dispatch]; results of older runs, and after [close], are dropped, and the
 * git of a load that a newer one replaces, or that is still running at [close], is cancelled. A refresh after a failed
 * page reads only the rows loaded before it. Used on the JavaFX thread (or whatever [dispatch] stands for in tests).
 *
 * Each load is a task of [tasks] (ADR 0061), "Loading history of <name>", started on [background] unless its run is
 * dropped by then. Its ✕ stops the load's git: the rows loaded before stay. A cancelled further page loads again with
 * the next scroll; a cancelled first load or refresh is [FileHistoryState.CANCELLED] ("Loading cancelled. Refresh to
 * load.", in the header while rows are shown) until a refresh, or until the history is shown again.
 */
internal class FileHistory(
    val root: Path,
    val key: FileHistoryKey,
    private val load: (Path, String, String?, Int, Int, GitCancellation) -> GitFileHistoryPage?,
    private val loadRefs: (Path) -> GitRefs?,
    private val background: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
    private val tasks: BackgroundTasks,
    private val pageSize: Int = GitLogPages.PAGE_SIZE,
    private val prefetch: Int = GitLogPages.PREFETCH,
) {
    /** The loaded commits, newest first. */
    val rows: ObservableList<GitLogRow> = FXCollections.observableArrayList()

    private val _state = ReadOnlyObjectWrapper(FileHistoryState.IDLE)
    val state: ReadOnlyObjectProperty<FileHistoryState> = _state.readOnlyProperty

    private val _multiplePaths = ReadOnlyBooleanWrapper(false)

    /** Whether the loaded rows have the file at more than one path (it was renamed), so that each row shows its path. */
    val multiplePaths: ReadOnlyBooleanProperty = _multiplePaths.readOnlyProperty

    /** The hash of the selected row, if any; kept selected by [select] when a refresh still has it. */
    var selectedHash: () -> String? = { null }

    /** Selects the row at the given index, or none for -1; called after the rows were replaced. */
    var select: (Int) -> Unit = {}

    /**
     * Told when a load fails or a first load or refresh is cancelled while rows are shown, which then stay (the table's
     * placeholder cannot say it; [state]'s message does).
     */
    var failedWithRows: () -> Unit = {}

    /** The refs read with the last first page; `null` before, or if they could not be read. */
    var refs: GitRefs? = null
        private set

    /** The file's change at each loaded commit, by hash. */
    private val files = HashMap<String, GitChangedFile>()

    private val run = AtomicLong()

    /** Stops the git of the load going on, if any. */
    private var cancellation: GitCancellation? = null
    private var stale = true
    private var complete = false
    private var loadingMore = false
    private var firstLoad = true
    private var closed = false

    /** Whether the history is shown; showing it loads the first page if nothing (or something outdated) is loaded. */
    var visible = false
        set(value) {
            field = value
            if (value && stale) reload()
        }

    /** The file's change at the commit of [row]: its path there and, if renamed or copied, the path it came from. */
    fun fileOf(row: GitLogRow): GitChangedFile? = files[row.commit.hash]

    /** Loads the rows again now if [visible], else when shown next. */
    fun refresh() {
        if (closed) return
        if (visible) reload() else stale = true
    }

    /** Loads the next page if the row at [index] is near the last loaded one and there may be more. */
    fun shown(index: Int) {
        if (index >= rows.size - prefetch) loadMore()
    }

    /** Drops the results of loads still going; nothing is loaded anymore. */
    fun close() {
        closed = true
        run.incrementAndGet()
        cancellation?.cancel()
    }

    /** A new cancellation for the next load; the git of the one before is stopped. */
    private fun nextCancellation(): GitCancellation {
        cancellation?.cancel()
        return GitCancellation().also { cancellation = it }
    }

    private fun reload() {
        if (closed) return
        stale = false
        loadingMore = false
        _state.set(FileHistoryState.LOADING)
        val current = run.incrementAndGet()
        val cancellation = nextCancellation()
        // As many as were loaded (not a page that failed), so that a selected row further down stays.
        val limit = maxOf(pageSize, rows.size)
        loadInBackground(
            current,
            cancellation,
            {
                val page = load(root, key.path, key.start, 0, limit, cancellation)
                page to if (page != null && run.get() == current) loadRefs(root) else null
            },
        ) { page, refs, stopped ->
            if (page == null && stopped) {
                // The rows shown stay, outdated, and no further page loads: a refresh loads them again.
                stale = true
                _state.set(FileHistoryState.CANCELLED)
                if (rows.isNotEmpty()) failedWithRows()
            } else if (page == null) {
                failed()
            } else {
                val keep = selectedHash()
                this.refs = refs
                files.clear()
                rows.setAll(rowsOf(page))
                complete = page.complete
                val index =
                    when {
                        keep != null -> rows.indexOfFirst { it.commit.hash == keep }
                        firstLoad -> rows.indexOfFirst { it.commit.hash == key.start }.takeIf { it >= 0 } ?: 0
                        else -> -1
                    }
                firstLoad = false
                select(index.takeIf { it < rows.size } ?: -1)
                _state.set(if (rows.isEmpty()) FileHistoryState.EMPTY else FileHistoryState.LOADED)
            }
        }
    }

    /** Loads the next page, unless one is loading, there are no more or the rows are outdated. */
    private fun loadMore() {
        if (_state.get() != FileHistoryState.LOADED || stale || complete || loadingMore || closed) return
        loadingMore = true
        val current = run.get()
        val cancellation = nextCancellation()
        val skip = rows.size
        loadInBackground(
            current,
            cancellation,
            { load(root, key.path, key.start, skip, pageSize, cancellation) to null },
        ) { page, _, stopped ->
            loadingMore = false
            when {
                // Still loaded: the next scroll loads the page again.
                page == null && stopped -> {}

                page == null -> {
                    failed()
                }

                else -> {
                    rows.addAll(rowsOf(page))
                    complete = page.complete
                }
            }
        }
    }

    /**
     * Runs [read] on [background] as the task of a load, unless the run [current] is dropped by then, and hands its page
     * and refs to [handle] through [dispatch] while [current] is the run, with whether the task's ✕ [stopped][handle]
     * it. The ✕ stops the load's git through [cancellation]. The task ends finished with a page, cancelled without one
     * if its git was stopped (by ✕ or a newer load), else failed.
     */
    private fun loadInBackground(
        current: Long,
        cancellation: GitCancellation,
        read: () -> Pair<GitFileHistoryPage?, GitRefs?>,
        handle: (page: GitFileHistoryPage?, refs: GitRefs?, stopped: Boolean) -> Unit,
    ) {
        val stopped = AtomicBoolean()
        background {
            if (run.get() != current) return@background
            val task =
                tasks.start("Loading history of ${key.name}") {
                    stopped.set(true)
                    cancellation.cancel()
                }
            var thrown: RuntimeException? = null
            val (page, refs) =
                try {
                    read()
                } catch (e: RuntimeException) {
                    // A failed page, not a history that stays loading.
                    thrown = e
                    null to null
                } catch (t: Throwable) {
                    task.failed(t)
                    throw t
                }
            when {
                thrown != null -> task.failed(thrown)
                page != null -> task.close()
                cancellation.cancelled -> task.failed(CancellationException("Cancelled"))
                else -> task.failed(FileHistoryState.FAILED.message(key.name))
            }
            dispatch {
                if (run.get() == current) handle(page, refs, stopped.get())
            }
        }
    }

    private fun failed() {
        _state.set(FileHistoryState.FAILED)
        if (rows.isNotEmpty()) failedWithRows()
    }

    /** The rows of [page], with the refs pointing at their commits; notes the file's change at each. */
    private fun rowsOf(page: GitFileHistoryPage): List<GitLogRow> {
        val rows =
            page.entries.map { entry ->
                files[entry.commit.hash] = entry.file
                val at = refs?.at(entry.commit.hash).orEmpty()
                val head = at.firstOrNull()?.kind == GitRefKind.HEAD
                GitLogRow(entry.commit, GraphRow.NONE, at, refs?.currentBranch?.takeIf { head })
            }
        _multiplePaths.set(files.values.mapTo(HashSet()) { it.path }.size > 1)
        return rows
    }
}
