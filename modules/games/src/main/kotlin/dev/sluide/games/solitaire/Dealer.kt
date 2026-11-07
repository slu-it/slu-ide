package dev.sluide.games.solitaire

import dev.sluide.games.solitaire.solver.Deal
import java.util.concurrent.Executor

/**
 * Finds the deals of [SolitaireModel]: on [dealing] (one background thread), the next deal of the current [mode] in
 * advance. Every result comes back through [fx]; a result that is no longer wanted (a newer request, another mode) is
 * dropped and its search stops through its cancel flag. FX thread; the background tasks read only what they captured
 * and their own [Job].
 */
internal class Dealer(
    mode: Mode,
    private val findDeal: (Mode, Int, () -> Boolean) -> Deal?,
    private val dealIfWinnable: (Mode, Int, () -> Boolean) -> Deal?,
    private val dealing: Executor,
    private val fx: Executor,
    private val randomNumber: () -> Int,
) {
    /** The cancel flag of one background search; read by the search's thread. */
    private class Job(
        val mode: Mode,
    ) {
        @Volatile
        var cancelled = false
    }

    /** The mode of new deals; a change drops the deal fetched in advance and stops one being fetched for the old mode. */
    var mode: Mode = mode
        set(value) {
            if (field == value) return
            field = value
            if (prefetched?.mode != value) prefetched = null
            prefetching?.takeIf { it.mode != value }?.cancelled = true
        }

    /** Counts the requests; only the latest one is answered. */
    var generation = 0
        private set

    private var job: Job? = null
    private var prefetched: Deal? = null
    private var prefetching: Job? = null

    /** New Game waits for the matching prefetch in flight instead of searching a second time. */
    private var waiting: ((Deal?) -> Unit)? = null

    /**
     * A new deal of [mode] for [done] (null when none was found): the one fetched in advance at once, else the one being
     * fetched, else a search from a random number. Replaces the request before.
     */
    fun deal(done: (Deal?) -> Unit) {
        supersede()
        val ready = prefetched
        val inFlight = prefetching
        when {
            ready != null -> {
                prefetched = null
                done(ready)
            }

            inFlight != null && !inFlight.cancelled -> {
                waiting = done
            }

            else -> {
                search(done)
            }
        }
    }

    /**
     * Deal [number] of [mode] if it is proven winnable ([done]'s first argument), else the next winnable one after it
     * (the second); both null when none was found. Replaces the request before; leaves the deal fetched in advance.
     */
    fun request(
        number: Int,
        done: (Deal?, Deal?) -> Unit,
    ) {
        supersede()
        val mine = generation
        val job = Job(mode).also { this.job = it }
        dealing.execute {
            val exact = attempt { dealIfWinnable(job.mode, number, job::cancelled) }
            val alternative =
                if (exact == null && !job.cancelled) {
                    attempt { findDeal(job.mode, number % Deals.MAX_NUMBER + 1, job::cancelled) }
                } else {
                    null
                }
            fx.execute {
                if (mine != generation) return@execute
                this.job = null
                done(exact, alternative)
            }
        }
    }

    /** Fetches the next deal of [mode] unless one is ready or being fetched. */
    fun prefetch() {
        if (prefetched != null || prefetching != null) return
        val job = Job(mode).also { prefetching = it }
        val number = randomNumber()
        dealing.execute {
            val deal = attempt { findDeal(job.mode, number, job::cancelled) }
            fx.execute { prefetched(job, deal) }
        }
    }

    private fun prefetched(
        job: Job,
        deal: Deal?,
    ) {
        prefetching = null
        val fits = job.mode == mode && !job.cancelled
        val wanted = waiting
        when {
            wanted != null -> {
                waiting = null
                if (fits && deal != null) wanted(deal) else search(wanted)
            }

            fits -> {
                prefetched = deal
            }

            // A prefetch of another mode is dropped: the mode change started a deal, which fetches the next one when
            // it is played.
        }
    }

    private fun search(done: (Deal?) -> Unit) {
        val mine = generation
        val job = Job(mode).also { this.job = it }
        val number = randomNumber()
        dealing.execute {
            val deal = attempt { findDeal(job.mode, number, job::cancelled) }
            fx.execute {
                if (mine != generation) return@execute
                this.job = null
                done(deal)
            }
        }
    }

    private fun supersede() {
        generation++
        job?.cancelled = true
        job = null
        waiting = null
    }

    private fun attempt(find: () -> Deal?): Deal? =
        try {
            find()
        } catch (e: Throwable) {
            // Errors too (a deep recursion, say): without a result the model would wait for the deal forever.
            System
                .getLogger(Dealer::class.java.name)
                .log(System.Logger.Level.WARNING, "Solitaire deal failed", e)
            null
        }
}
