package dev.sluide.gitlog

import dev.sluide.git.GitCommit
import dev.sluide.git.GitInProgress
import dev.sluide.git.GitLogFilter
import dev.sluide.git.GitLogPage
import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
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

    /** No commits pass the filter (ADR 0039). */
    NO_MATCH("No commits match the filters."),
    FAILED("The git log could not be read."),
}

/** Why [GitLogPages.loadUntil] did not find a commit. */
internal enum class CommitMissing {
    /** The log is complete without it, e.g. as the filters hide it. */
    NOT_IN_LOG,

    /** It is not among the first [GitLogPages.SEEK_LIMIT] rows. */
    BEYOND_LIMIT,
}

/** What [GitLogPages.loadUntil] waits for. */
private class Seek(
    val hash: String,
    val found: (Int) -> Unit,
    val missing: (CommitMissing) -> Unit,
)

/**
 * A row of the git log: a commit, its part of the commit graph (ADR 0036) and the [refs] pointing at it, ordered as
 * [GitRefs.at] orders them (ADR 0037). [currentBranch] is the full refname of the checked-out branch if `HEAD` is
 * among the refs and attached to it, else `null`.
 */
internal data class GitLogRow(
    val commit: GitCommit,
    val graph: GraphRow,
    val refs: List<GitRef> = emptyList(),
    val currentBranch: String? = null,
)

/**
 * Which commits the git log shows and when they are loaded (ADR 0035), without JavaFX controls: nothing is loaded
 * until the log is [visible]; then a first page of [pageSize] commits, and the next page whenever a row within
 * [prefetch] rows of the last loaded one is [shown], until a page says it is complete. Each row carries its part of
 * the commit graph; a further page continues the graph of the rows before it, a reload starts it again. The refs are
 * read with [loadRefs] right after every first page and given to the rows of all pages until the next reload; if they
 * cannot be read, the rows have none. With the refs, [loadPending] reads what git is in the middle of ([pending],
 * ADR 0042); a failed first page forgets it.
 *
 * Only the commits that pass the [filter] are loaded (ADR 0039). If its text could be a hash, [loadHashMatch] is asked
 * right after the refs for the commit it names, which then is the first row, and not repeated in any page. Under a
 * filter that hides commits the rows have no graph ([GraphRow.NONE]); with first parents only, a row's graph follows
 * only its first parent.
 *
 * [loadUntil] loads pages until a commit is there (ADR 0040), for the branches pane.
 *
 * [load] and [loadRefs] run on [background], their results are handled through [dispatch]. Every [open] and reload
 * starts a new run; results of older runs are dropped. Used on the JavaFX thread (or whatever [dispatch] stands for
 * in tests).
 */
internal class GitLogPages(
    private val load: (Path, GitLogFilter, Int, Int) -> GitLogPage?,
    private val loadRefs: (Path) -> GitRefs?,
    private val loadHashMatch: (Path, GitLogFilter) -> GitCommit?,
    private val background: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
    private val pageSize: Int = PAGE_SIZE,
    private val prefetch: Int = PREFETCH,
    private val seekLimit: Int = SEEK_LIMIT,
    private val loadPending: (Path) -> GitInProgress? = { null },
) {
    /** The loaded commits with their graph, newest first. */
    val rows: ObservableList<GitLogRow> = FXCollections.observableArrayList()

    private val _state = ReadOnlyObjectWrapper(GitLogState.IDLE)
    val state: ReadOnlyObjectProperty<GitLogState> = _state.readOnlyProperty

    /** The hashes of the selected rows; kept selected by [select] when a reload still has them. */
    var selectedHashes: () -> Collection<String> = { emptyList() }

    /** Selects the rows at the given indices, called after a reload replaced the rows. */
    var select: (List<Int>) -> Unit = {}

    /** The repository shown, `null` for none. */
    var root: Path? = null
        private set
    private var layout = GraphLayout()

    /** The refs read with the last first page; `null` before, or if they could not be read. */
    var refs: GitRefs? = null
        private set

    /**
     * What git is in the middle of (a cherry-pick or revert, a merge…), read with the last first page; `null` if
     * nothing, before, or after a first page failed.
     */
    var pending: GitInProgress? = null
        private set

    /** The commits read from git for the rows, which may have one more: the commit the text names as a hash. */
    private var loaded = 0

    /** The hash of the commit the text names, shown as the first row; `null` if there is none. */
    private var hashRow: String? = null

    private var currentFilter = GitLogFilter()

    /** The current run; read on the background thread to skip runs that are no longer wanted. */
    private val run = AtomicLong()
    private var stale = false
    private var complete = false
    private var loadingMore = false

    /** The commit [loadUntil] loads pages for, if any. */
    private var seek: Seek? = null

    /** Whether the log is shown; showing it loads the first page if nothing (or something outdated) is loaded. */
    var visible = false
        set(value) {
            field = value
            if (value && stale) reload()
        }

    /**
     * Which commits are loaded; a change reloads the first page as [refresh] does, and [open] resets it. Results of
     * loads for an earlier filter are dropped.
     */
    var filter: GitLogFilter
        get() = currentFilter
        set(value) {
            if (currentFilter == value) return
            currentFilter = value
            seek = null
            if (root == null) return
            if (visible) {
                reload()
            } else {
                // Drops loads for the earlier filter; the next showing reloads.
                run.incrementAndGet()
                loadingMore = false
                stale = true
            }
        }

    /**
     * Shows the log of the repository at [root] with the default filter; `null` shows nothing. Loads only once
     * [visible].
     */
    fun open(root: Path?) {
        this.root = root
        currentFilter = GitLogFilter()
        run.incrementAndGet()
        rows.clear()
        layout = GraphLayout()
        refs = null
        pending = null
        loaded = 0
        hashRow = null
        complete = false
        loadingMore = false
        seek = null
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
        if (index >= rows.size - prefetch) loadMore()
    }

    /**
     * Hands the index of the row of the commit [hash] to [found], loading further pages until it is there; if the log
     * is complete without it, or [seekLimit] rows are loaded, tells [missing] why instead. While the first page is
     * still loading, and after a [refresh], waits for the first page. A newer call replaces this one, and
     * [stopLoadingUntil], [open], a change of the [filter] or a failure end it without telling either.
     */
    fun loadUntil(
        hash: String,
        found: (Int) -> Unit,
        missing: (CommitMissing) -> Unit,
    ) {
        seek = Seek(hash, found, missing)
        continueSeek()
    }

    /** The commit [loadUntil] is still loading pages for, if any. */
    val loadingUntil: String? get() = seek?.hash

    /** Stops what [loadUntil] asked for. */
    fun stopLoadingUntil() {
        seek = null
    }

    /** Drops the results of runs still going. */
    fun close() {
        root = null
        seek = null
        run.incrementAndGet()
    }

    /** Loads the next page, unless one is loading, there are no more or the rows are outdated. */
    private fun loadMore() {
        if (_state.get() != GitLogState.LOADED || stale || complete || loadingMore) return
        val root = root ?: return
        loadingMore = true
        // Counted in git's commits: the rows may have the hash row on top.
        start(root, loaded) { page, _, _, _ ->
            loadingMore = false
            if (page == null) {
                _state.set(GitLogState.FAILED)
            } else {
                rows.addAll(layOut(page.commits.filter { it.hash != hashRow }))
                loaded += page.commits.size
                complete = page.complete
            }
            continueSeek()
        }
    }

    /** Takes the [seek] a step further: done if its commit is loaded or cannot be, else the next page. */
    private fun continueSeek() {
        val seek = seek ?: return
        val state = _state.get()
        if (state == GitLogState.LOADING) return
        if (stale || state == GitLogState.IDLE || state == GitLogState.FAILED) {
            this.seek = null
            return
        }
        val index = rows.indexOfFirst { it.commit.hash == seek.hash }
        val missing =
            when {
                index >= 0 -> null

                complete -> CommitMissing.NOT_IN_LOG

                rows.size >= seekLimit -> CommitMissing.BEYOND_LIMIT

                // The next page, or the one loading, continues.
                else -> return loadMore()
            }
        this.seek = null
        if (missing == null) seek.found(index) else seek.missing(missing)
    }

    private fun reload() {
        val root = root ?: return
        stale = false
        loadingMore = false
        _state.set(GitLogState.LOADING)
        run.incrementAndGet()
        start(root, 0) { page, refs, match, pending ->
            if (page == null) {
                // Keeps the rows there are; no more pages until the next reload. What was in progress may be over.
                this.pending = null
                _state.set(GitLogState.FAILED)
                seek = null
                return@start
            }
            val keep = selectedHashes().toSet()
            layout = GraphLayout()
            this.refs = refs
            this.pending = pending
            hashRow = match?.hash
            rows.setAll(layOut(listOfNotNull(match) + page.commits.filter { it.hash != match?.hash }))
            loaded = page.commits.size
            complete = page.complete
            // Before the state, whose listeners may select a row of their own (the branches pane).
            if (keep.isNotEmpty()) select(rows.indices.filter { rows[it].commit.hash in keep })
            _state.set(
                when {
                    rows.isNotEmpty() -> GitLogState.LOADED
                    filter.isDefault -> GitLogState.EMPTY
                    else -> GitLogState.NO_MATCH
                },
            )
            // A seek from before the reload goes on in the new rows.
            continueSeek()
        }
    }

    private fun layOut(commits: List<GitCommit>) =
        commits.map { commit ->
            val at = refs?.at(commit.hash).orEmpty()
            val head = at.firstOrNull()?.kind == GitRefKind.HEAD
            val graph =
                when {
                    // Its parents may be hidden, and git does not rewrite them: no graph rather than a wrong one.
                    filter.hidesCommits -> GraphRow.NONE

                    // Git's %P still lists all parents.
                    filter.firstParent -> layout.next(commit.hash, commit.parents.take(1))

                    else -> layout.next(commit.hash, commit.parents)
                }
            GitLogRow(commit, graph, at, refs?.currentBranch?.takeIf { head })
        }

    /**
     * Loads the page after [skip] commits read from git with the current [filter]; for the first page then the refs,
     * the cherry-pick or revert in progress and, if the filter's text could be a hash, the commit it names; and hands
     * them to [handle].
     */
    private fun start(
        root: Path,
        skip: Int,
        handle: (GitLogPage?, GitRefs?, GitCommit?, GitInProgress?) -> Unit,
    ) {
        val current = run.get()
        val filter = filter
        background {
            if (run.get() != current) return@background
            val page = load(root, filter, skip, pageSize)
            val first = skip == 0 && page != null
            val refs = if (first && run.get() == current) loadRefs(root) else null
            val pending = if (first && run.get() == current) loadPending(root) else null
            val match =
                if (first && run.get() == current && HASH_PREFIX.matches(filter.text.trim())) {
                    loadHashMatch(root, filter)
                } else {
                    null
                }
            dispatch { if (run.get() == current) handle(page, refs, match, pending) }
        }
    }

    companion object {
        const val PAGE_SIZE = 500
        const val PREFETCH = 50

        /** How many rows [loadUntil] loads at most. */
        const val SEEK_LIMIT = 10_000

        /** A text that may name a commit by its hash. */
        private val HASH_PREFIX = Regex("[0-9a-fA-F]{4,64}")
    }
}
