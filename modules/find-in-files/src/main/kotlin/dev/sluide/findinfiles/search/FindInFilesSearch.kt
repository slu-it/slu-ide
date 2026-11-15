package dev.sluide.findinfiles.search

import java.time.Duration
import java.util.concurrent.Executor

private val log = System.getLogger(FindInFilesSearch::class.java.name)

/** Runs [task] once after [delay] (tests replace it by a clock they advance by hand); closing cancels it. */
internal fun interface SearchScheduler {
    fun schedule(
        delay: Duration,
        task: Runnable,
    ): AutoCloseable
}

/** What a search tells the popup; all calls on the FX thread, never for a search that was replaced or cancelled. */
internal interface SearchListener {
    /** The query is too short: no results, no status. */
    fun cleared()

    /** A walk starts: the results so far are gone. */
    fun started()

    /** More [files] with matches, in walk order; [status] counts all listed so far. */
    fun added(
        files: List<FileHits>,
        status: String,
    )

    /** The walk ended with [outcome], after the last [added]. */
    fun finished(
        outcome: SearchOutcome,
        status: String,
    )

    /** The walk threw (logged); the next change searches again. */
    fun failed()
}

/**
 * Find in Files' search, free of JavaFX: a request goes in ([changed], [runNow]), the [listener] hears the results.
 * A query of at least [MIN_LENGTH] characters (blanks count: they can be searched) is searched after [delay]; new input
 * restarts the wait and cancels a running walk, whose results then never reach the listener. [walk] runs on
 * [background]; the files it finds reach the listener through [runOnFx] in batches, at most one every [batchEvery]
 * (measured with [nanoTime]). All calls except the walk happen on the FX thread.
 */
internal class FindInFilesSearch(
    private val scheduler: SearchScheduler,
    private val delay: () -> Duration = { Duration.ofMillis(250) },
    private val background: Executor,
    private val runOnFx: (Runnable) -> Unit,
    private val batchEvery: Duration = Duration.ofMillis(100),
    private val nanoTime: () -> Long = System::nanoTime,
    private val walk: (SearchRequest, cancelled: () -> Boolean, onFile: (FileHits) -> Unit) -> SearchOutcome?,
    private val listener: SearchListener,
) {
    private var pending: AutoCloseable? = null

    /** Counts the walks started. */
    private var starts = 0

    /** The walk running now; replaced for every walk. */
    private var run: Run? = null

    /** The query or its options changed: searches [request] after the delay, or clears for a too short query. */
    fun changed(request: SearchRequest) {
        cancel()
        if (tooShort(request)) return listener.cleared()
        val before = starts
        val handle = scheduler.schedule(delay()) { start(request) }
        // A scheduler may run the task before it returns (a zero delay): then nothing is pending.
        if (starts == before) pending = handle
    }

    /** Searches [request] at once (opening with a remembered query, widening the scope), or clears if too short. */
    fun runNow(request: SearchRequest) {
        cancel()
        if (tooShort(request)) return listener.cleared()
        start(request)
    }

    /** Stops the wait and the walk; nothing more reaches the listener until the next request. */
    fun cancel() {
        pending?.close()
        pending = null
        run?.cancelled = true
        run = null
    }

    private fun tooShort(request: SearchRequest) = request.query.length < MIN_LENGTH

    private fun start(request: SearchRequest) {
        pending = null
        starts++
        val current = Run(request).also { run = it }
        listener.started()
        background.execute(current::search)
    }

    /**
     * One walk. Found files collect on the walk thread; when a batch is due (checked whenever the walk finds a file or
     * asks whether it is cancelled, which it does often) they are posted to the FX thread as one batch.
     */
    private inner class Run(
        val request: SearchRequest,
    ) {
        @Volatile var cancelled = false

        /** Found since the last post. Walk thread only. */
        private var found = ArrayList<FileHits>()

        /** When the last batch was posted; `null` before the first. Walk thread only. */
        private var lastPost: Long? = null

        /** Delivered so far. FX thread only. */
        private var matches = 0
        private var files = 0

        fun search() {
            val startedAt = nanoTime()
            val outcome =
                try {
                    walk(request, ::isCancelled) { hits ->
                        found += hits
                        postIfDue()
                    }
                } catch (e: Exception) {
                    log.log(System.Logger.Level.ERROR, "Find in Files failed", e)
                    runOnFx { if (!cancelled) fail() }
                    return
                } ?: return
            if (outcome is SearchOutcome.Done) {
                val millis = Duration.ofNanos(nanoTime() - startedAt).toMillis()
                log.log(
                    System.Logger.Level.INFO,
                    "Find in Files: ${outcome.matches} matches in ${outcome.files} files, " +
                        "${outcome.skipped} skipped, in $millis ms",
                )
            }
            val rest = takeFound()
            runOnFx { if (!cancelled) finish(rest, outcome) }
        }

        /** Whether the walk is cancelled; also posts a due batch. Walk thread. */
        private fun isCancelled(): Boolean {
            postIfDue()
            return cancelled
        }

        private fun postIfDue() {
            val now = nanoTime()
            val last = lastPost
            if (found.isEmpty() || (last != null && now - last < batchEvery.toNanos())) return
            lastPost = now
            val batch = takeFound()
            runOnFx { if (!cancelled) deliver(batch) }
        }

        private fun takeFound(): List<FileHits> = found.also { found = ArrayList() }

        private fun deliver(batch: List<FileHits>) {
            if (batch.isEmpty()) return
            matches += batch.sumOf { it.matchCount }
            files += batch.size
            listener.added(batch, statusText(SearchState.Running(matches, files)))
        }

        private fun finish(
            rest: List<FileHits>,
            outcome: SearchOutcome,
        ) {
            deliver(rest)
            run = null
            listener.finished(outcome, statusText(SearchState.Finished(outcome, request.limits)))
        }

        private fun fail() {
            run = null
            listener.failed()
        }
    }

    companion object {
        /** The fewest characters a query needs. */
        const val MIN_LENGTH = 2
    }
}
