package dev.sluide.gitlog.data

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.git.GitCancellation
import dev.sluide.git.GitCommit
import dev.sluide.git.GitComparisonCounts
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
import java.util.concurrent.CancellationException
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

    /** The log could not be read (a page failed, or nothing is loaded), so it cannot be looked for (ADR 0045). */
    UNREADABLE,
}

/** Which commits a comparison of a ref with `HEAD` lists (ADR 0044). */
internal enum class ComparisonSide {
    /** Those of the ref that are not in `HEAD`. */
    REF_ONLY,

    /** Those of `HEAD` that are not in the ref. */
    HEAD_ONLY,
}

/** The git log compares [ref] (a branch, remote branch or tag) with `HEAD`, listing the commits of [side] (ADR 0044). */
internal data class LogComparison(
    val ref: GitRef,
    val side: ComparisonSide = ComparisonSide.REF_ONLY,
) {
    /** [filter] limited to the commits of [side]: the range `<ref> ^HEAD` or `HEAD ^<ref>`, instead of its revisions. */
    fun limit(filter: GitLogFilter): GitLogFilter =
        when (side) {
            ComparisonSide.REF_ONLY -> filter.copy(revisions = listOf(ref.name), excluded = listOf(HEAD))
            ComparisonSide.HEAD_ONLY -> filter.copy(revisions = listOf(HEAD), excluded = listOf(ref.name))
        }

    /** Why the refs [refs] end this comparison: its ref is gone or checked out; `null` if they do not. */
    fun endedBy(refs: GitRefs): ComparisonEnd? =
        when {
            refs.refs.none { it.name == ref.name } -> ComparisonEnd.GONE
            refs.currentBranch == ref.name -> ComparisonEnd.CURRENT
            else -> null
        }

    private companion object {
        const val HEAD = "HEAD"
    }
}

/** Why a comparison ended by itself, after a refresh (ADR 0044). */
internal enum class ComparisonEnd {
    /** The compared ref no longer exists. */
    GONE,

    /** The compared ref is the checked-out branch now. */
    CURRENT,
}

/** What [GitLogPages.loadUntil] waits for; [target] names the commit, e.g. a branch. */
private class Seek(
    val hash: String,
    val target: String,
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
 * ADR 0042) and [loadRemotes] the names of the remotes ([remotes], ADR 0043); a failed first page forgets both.
 *
 * Only the commits that pass the [filter] are loaded (ADR 0039). If its text could be a hash, [loadHashMatch] is asked
 * right after the refs for the commit it names, which then is the first row, and not repeated in any page. Under a
 * filter that hides commits the rows have no graph ([GraphRow.NONE]); with first parents only, a row's graph follows
 * only its first parent.
 *
 * [loadUntil] loads pages until a commit is there (ADR 0040), for the branches pane.
 *
 * While a [comparison] is set (ADR 0044), the rows are the commits of one side of it that pass the filter without its
 * revisions, and [loadCounts] reads how many commits of each side pass the filter with every first page ([counts]),
 * so that they match the list; no hash match is
 * looked for. A refresh whose refs no longer have the compared ref, or have it checked out, ends the comparison: the
 * log is loaded again without it and [comparisonEnded] is told.
 *
 * [load] and [loadRefs] run on [background], their results are handled through [dispatch]. Every [open] and reload
 * starts a new run; results of older runs are dropped, and their git is stopped through the [GitCancellation] every
 * load gets (ADR 0062): a reload, another filter, [open] and [close] stop a page or reload still running, also one
 * that [loadUntil] asked for. Used on the JavaFX thread (or whatever [dispatch] stands for in tests). Each page is a
 * task of [tasks] (ADR 0061), started on [background] unless its run is dropped by then: "Loading Git log" (a first
 * page), "Loading more commits", or "Searching Git log for <name>" for [loadUntil]; a page whose git was stopped ends
 * it as cancelled.
 */
internal class GitLogPages(
    private val load: (Path, GitLogFilter, Int, Int, GitCancellation) -> GitLogPage?,
    private val loadRefs: (Path, GitCancellation) -> GitRefs?,
    private val loadHashMatch: (Path, GitLogFilter, GitCancellation) -> GitCommit?,
    private val background: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
    private val tasks: BackgroundTasks,
    private val pageSize: Int = PAGE_SIZE,
    private val prefetch: Int = PREFETCH,
    private val seekLimit: Int = SEEK_LIMIT,
    private val loadPending: (Path, GitCancellation) -> GitInProgress? = { _, _ -> null },
    private val loadRemotes: (Path, GitCancellation) -> List<String>? = { _, _ -> null },
    private val loadCounts: (Path, String, GitLogFilter, GitCancellation) -> GitComparisonCounts? =
        { _, _, _, _ -> null },
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

    /**
     * The names of all remotes, read with the last first page; `null` before, after a failure, or if they could not be
     * read.
     */
    var remotes: List<String>? = null
        private set

    /** The commits read from git for the rows, which may have one more: the commit the text names as a hash. */
    private var loaded = 0

    /** The hash of the commit the text names, shown as the first row; `null` if there is none. */
    private var hashRow: String? = null

    private var currentFilter = GitLogFilter()

    /**
     * The comparison of a ref with `HEAD` the rows show (ADR 0044), `null` for none; a change reloads the first page as
     * a change of the [filter] does. [open] ends it.
     */
    var comparison: LogComparison?
        get() = currentComparison
        set(value) {
            if (currentComparison == value) return
            if (currentComparison?.ref?.name != value?.ref?.name) counts = null
            currentComparison = value
            filterChanged()
        }

    private var currentComparison: LogComparison? = null

    /**
     * How many commits each side of the [comparison] has, read with the last first page; `null` while not comparing,
     * before they are read, or if they could not be read.
     */
    var counts: GitComparisonCounts? = null
        private set

    /** Told when a refresh ended the [comparison] by itself, after the log is loaded again without it. */
    var comparisonEnded: (LogComparison, ComparisonEnd) -> Unit = { _, _ -> }

    /** The filter git loads the rows with: the [filter], limited to one side of the [comparison] if there is one. */
    val effectiveFilter: GitLogFilter get() = comparison?.limit(currentFilter) ?: currentFilter

    /** The current run; read on the background thread to skip runs that are no longer wanted. */
    private val run = AtomicLong()

    /** Stops the git of the load going on, if any: the run's own. */
    private var cancellation: GitCancellation? = null
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
            filterChanged()
        }

    /** Reloads the first page for a changed [filter] or [comparison], now or when shown next. */
    private fun filterChanged() {
        seek = null
        if (root == null) return
        if (visible) {
            reload()
        } else {
            // Drops loads for the earlier filter; the next showing reloads.
            newRun()
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
        currentComparison = null
        counts = null
        newRun()
        rows.clear()
        layout = GraphLayout()
        refs = null
        pending = null
        remotes = null
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
     * is complete without it, or [seekLimit] rows are loaded, tells [missing] why instead, also if the log cannot be read
     * (a page fails, [CommitMissing.UNREADABLE]). While the first page is still loading, and after a [refresh], waits for
     * the first page. A newer call replaces this one, and [stopLoadingUntil], [open] or a change of the [filter] end it
     * without telling either. The pages it loads are tasks named after [target] (a branch, or the hash as shown).
     */
    fun loadUntil(
        hash: String,
        found: (Int) -> Unit,
        missing: (CommitMissing) -> Unit,
        target: String = hash,
    ) {
        seek = Seek(hash, target, found, missing)
        continueSeek()
    }

    /** The commit [loadUntil] is still loading pages for, if any. */
    val loadingUntil: String? get() = seek?.hash

    /** Stops what [loadUntil] asked for; a page loading for it still comes. */
    fun stopLoadingUntil() {
        seek = null
    }

    /** Drops the results of runs still going and stops their git. */
    fun close() {
        root = null
        seek = null
        newRun()
    }

    /** Starts a new run: the results of older ones are dropped, their git stopped. */
    private fun newRun() {
        run.incrementAndGet()
        cancellation?.cancel()
        cancellation = null
    }

    /** Loads the next page, unless one is loading, there are no more or the rows are outdated. */
    private fun loadMore() {
        if (_state.get() != GitLogState.LOADED || stale || complete || loadingMore) return
        val root = root ?: return
        loadingMore = true
        val name = seek?.let { "Searching Git log for ${it.target}" } ?: "Loading more commits"
        // Counted in git's commits: the rows may have the hash row on top.
        start(root, loaded, name) { first ->
            val page = first.page
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
        if (stale) {
            this.seek = null
            return
        }
        val index = rows.indexOfFirst { it.commit.hash == seek.hash }
        val missing =
            when {
                index >= 0 -> null

                // The rows shown (if any) are all there is until a reload.
                state == GitLogState.IDLE || state == GitLogState.FAILED -> CommitMissing.UNREADABLE

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
        newRun()
        start(root, 0, "Loading Git log") { first ->
            val page = first.page
            val refs = first.refs
            if (page == null) {
                // Keeps the rows there are; no more pages until the next reload. What was in progress may be over.
                this.pending = null
                this.remotes = null
                _state.set(GitLogState.FAILED)
                continueSeek()
                return@start
            }
            val compared = comparison
            val end = compared?.let { refs?.let(it::endedBy) }
            if (compared != null && end != null) {
                // Loaded again without it; the rows of the range it no longer has are not shown.
                currentComparison = null
                counts = null
                reload()
                comparisonEnded(compared, end)
                return@start
            }
            val match = first.match
            val keep = selectedHashes().toSet()
            layout = GraphLayout()
            this.refs = refs
            this.pending = first.pending
            this.remotes = first.remotes
            this.counts = first.counts
            hashRow = match?.hash
            rows.setAll(layOut(listOfNotNull(match) + page.commits.filter { it.hash != match?.hash }))
            loaded = page.commits.size
            complete = page.complete
            // Before the state, whose listeners may select a row of their own (the branches pane).
            if (keep.isNotEmpty()) select(rows.indices.filter { rows[it].commit.hash in keep })
            _state.set(
                when {
                    rows.isNotEmpty() -> GitLogState.LOADED
                    effectiveFilter.isDefault -> GitLogState.EMPTY
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
                    currentFilter.hidesCommits -> GraphRow.NONE

                    // Git's %P still lists all parents.
                    currentFilter.firstParent -> layout.next(commit.hash, commit.parents.take(1))

                    else -> layout.next(commit.hash, commit.parents)
                }
            GitLogRow(commit, graph, at, refs?.currentBranch?.takeIf { head })
        }

    /** A page read from git, and for a first page what is read with it (`null` for further pages). */
    private class FirstPage(
        val page: GitLogPage?,
        val refs: GitRefs? = null,
        val match: GitCommit? = null,
        val pending: GitInProgress? = null,
        val remotes: List<String>? = null,
        val counts: GitComparisonCounts? = null,
    )

    /**
     * Loads the page after [skip] commits read from git with the [effectiveFilter]; for the first page then the refs,
     * the cherry-pick or revert in progress, the remotes, the counts of the comparison if there is one, and, if the
     * filter's text could be a hash (not while comparing), the commit it names; and hands them to [handle]. All that
     * is the task [name].
     */
    private fun start(
        root: Path,
        skip: Int,
        name: String,
        handle: (FirstPage) -> Unit,
    ) {
        val current = run.get()
        val token = GitCancellation().also { cancellation = it }
        val filter = effectiveFilter
        val barFilter = currentFilter
        val compared = comparison
        background {
            if (run.get() != current || token.cancelled) return@background
            val task = tasks.start(name)
            var thrown: RuntimeException? = null
            val loaded =
                try {
                    loadPage(root, filter, barFilter, compared, skip, current, token)
                } catch (e: RuntimeException) {
                    // A failed page, not a log that stays loading.
                    thrown = e
                    FirstPage(null)
                } catch (t: Throwable) {
                    task.failed(t)
                    throw t
                }
            when {
                thrown != null -> task.failed(thrown)

                // Its git was stopped, or what it read is dropped anyway: no failure.
                token.cancelled -> task.failed(CancellationException("Superseded"))

                loaded.page == null -> task.failed(GitLogState.FAILED.message)

                else -> task.close()
            }
            dispatch {
                if (run.get() == current) handle(loaded)
            }
        }
    }

    /** What [start] reads on the background thread; what goes with a first page only while [current] is the run. */
    private fun loadPage(
        root: Path,
        filter: GitLogFilter,
        barFilter: GitLogFilter,
        compared: LogComparison?,
        skip: Int,
        current: Long,
        token: GitCancellation,
    ): FirstPage {
        val page = load(root, filter, skip, pageSize, token)
        val first = skip == 0 && page != null

        fun <T> read(value: () -> T?): T? = if (first && run.get() == current && !token.cancelled) value() else null
        val refs = read { loadRefs(root, token) }
        val pending = read { loadPending(root, token) }
        val remotes = read { loadRemotes(root, token) }
        val counts = compared?.let { read { loadCounts(root, it.ref.name, barFilter, token) } }
        val match =
            if (compared == null &&
                HASH_PREFIX.matches(filter.text.trim())
            ) {
                read { loadHashMatch(root, filter, token) }
            } else {
                null
            }
        return FirstPage(page, refs, match, pending, remotes, counts)
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
