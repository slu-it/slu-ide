package dev.sluide.fileexplorer.search

import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.time.Duration

private val log = System.getLogger(ExplorerSearch::class.java.name)

/** Runs [task] once after [delay] (tests replace it by a clock they advance by hand); closing cancels it. */
internal fun interface SearchScheduler {
    fun schedule(
        delay: Duration,
        task: Runnable,
    ): AutoCloseable
}

/**
 * The explorer search's state, free of JavaFX: the text goes in ([textChanged]), the status line comes out ([onStatus]).
 * A text of at least [MIN_LENGTH] characters is searched after [delay] (a new text restarts the wait and cancels a
 * running walk); [walk] runs on [background], its result is applied on the FX thread ([runOnFx]) only while it is still
 * the text. Fewer characters or none end the search ([apply] with `null`). All calls except the background walk happen
 * on the FX thread.
 */
internal class ExplorerSearch(
    private val scheduler: SearchScheduler,
    private val delay: () -> Duration,
    private val background: Executor,
    private val runOnFx: (Runnable) -> Unit,
    /** The top-level folders, read on the FX thread when a walk starts. */
    private val roots: () -> List<Path>,
    /** Walks the roots for the query until `cancelled`; `null` when cancelled. Runs on [background]. */
    private val walk: (roots: List<Path>, query: NameQuery, cancelled: () -> Boolean) -> SearchResult?,
    /** Narrows the tree to the matches; `null` ends the search and restores the tree. */
    private val apply: (matches: List<Path>?) -> Unit,
    /** Drops what the explorer added to the results while searching; asked before a new text's results are applied. */
    private val resetExtras: () -> Unit,
    /** Whether a search is applied to the tree. */
    private val isActive: () -> Boolean,
    /** Tells the status line's text; empty for none. */
    private val onStatus: (String) -> Unit,
) {
    private var text = ""

    /** The text whose results are applied; `null` while none are. */
    private var appliedText: String? = null
    private var pending: AutoCloseable? = null

    /** Counts the walks started. */
    private var starts = 0

    /** The walk running now; replaced for every walk. */
    private var run: Run? = null

    /** Files changed while a walk of the same text ran: it runs once more when that walk is applied. */
    private var dirty = false

    /** What waits for the results of the current text ([whenCurrent]). */
    private var waiting: (() -> Unit)? = null

    /** The query the applied results are for; `null` while no results are applied. The cells highlight by it. */
    var query: NameQuery? = null
        private set

    /** The applied matches; empty while no results are applied. */
    var matches: Set<Path> = emptySet()
        private set

    /** The field's text changed. */
    fun textChanged(newText: String) {
        if (newText == text) return
        text = newText
        cancel()
        if (newText.length >= MIN_LENGTH) {
            schedule()
            return
        }
        appliedText = null
        query = null
        matches = emptySet()
        if (isActive()) apply(null)
        onStatus(if (newText.isEmpty()) "" else "Type at least $MIN_LENGTH characters")
    }

    /**
     * Files or the listing settings changed: searches the current text again, keeping the extras. A walk of the same
     * text is not cancelled; it runs once more when its results are applied. The status keeps the count meanwhile.
     */
    fun invalidated() {
        if (text.length < MIN_LENGTH) return
        when {
            run != null -> dirty = true
            pending == null -> schedule()
        }
    }

    /**
     * Runs [action] once the results of the current text are applied: now if they are (or the text is too short to
     * search), else after the walk for it, which starts at once if only the wait is pending. A later text change drops
     * it.
     */
    fun whenCurrent(action: () -> Unit) {
        if (text.length < MIN_LENGTH || text == appliedText) return action()
        waiting = action
        if (run == null) {
            cancelPending()
            start()
        }
    }

    /** The explorer shows other roots: stops the walk and forgets the text; the tree forgot the search already. */
    fun rootsChanged() {
        cancel()
        text = ""
        appliedText = null
        query = null
        matches = emptySet()
        onStatus("")
    }

    private fun schedule() {
        val before = starts
        val handle = scheduler.schedule(delay(), ::start)
        // A scheduler may run the task before it returns (a zero delay): then nothing is pending.
        if (starts == before) pending = handle
    }

    private fun start() {
        pending = null
        starts++
        val searched = text
        val current = Run(searched).also { run = it }
        dirty = false
        val folders = roots()
        val wanted = NameQuery(searched)
        if (searched != appliedText) onStatus("Searching…")
        background.execute {
            val result =
                try {
                    walk(folders, wanted) { current.cancelled }
                } catch (e: Exception) {
                    log.log(System.Logger.Level.ERROR, "The explorer search failed", e)
                    runOnFx { if (!current.cancelled) failed() }
                    return@execute
                }
            if (result != null) runOnFx { if (!current.cancelled) finish(current.text, wanted, result) }
        }
    }

    /** The walk threw: nothing waits for it any more; the tree stays as it is and the next change searches again. */
    private fun failed() {
        run = null
        waiting = null
        dirty = false
        onStatus("Search failed")
    }

    private fun finish(
        searched: String,
        wanted: NameQuery,
        result: SearchResult,
    ) {
        run = null
        val capped = result.truncated && result.matches.size >= LIMIT
        val found = result.matches.distinct()
        if (searched != appliedText) resetExtras()
        appliedText = searched
        query = wanted
        matches = found.toSet()
        apply(found)
        onStatus(statusFor(found.size, result.truncated, capped))
        val action = waiting
        waiting = null
        action?.invoke()
        if (dirty) {
            dirty = false
            schedule()
        }
    }

    private fun statusFor(
        count: Int,
        truncated: Boolean,
        capped: Boolean,
    ): String {
        val matches =
            when (count) {
                0 -> "No matches"
                1 -> "1 match"
                else -> "$count matches"
            }
        return when {
            capped -> "First $LIMIT matches, type more to narrow"
            truncated -> "$matches in the first $VISITED_TEXT entries, type more to narrow"
            else -> matches
        }
    }

    private fun cancelPending() {
        pending?.close()
        pending = null
    }

    private fun cancel() {
        cancelPending()
        run?.cancelled = true
        run = null
        dirty = false
        waiting = null
    }

    private class Run(
        val text: String,
    ) {
        @Volatile var cancelled = false
    }

    companion object {
        /** The fewest characters a search needs. */
        const val MIN_LENGTH = 3

        /** The most matches a search collects. */
        const val LIMIT = 100

        /** The most entries a walk visits. */
        const val MAX_VISITED = 200_000

        private val VISITED_TEXT = "%,d".format(java.util.Locale.ROOT, MAX_VISITED).replace(',', ' ')
    }
}
