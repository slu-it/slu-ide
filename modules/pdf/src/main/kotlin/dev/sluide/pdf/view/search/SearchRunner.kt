package dev.sluide.pdf.view.search

import java.util.concurrent.Executor

/**
 * Runs searches off the FX thread and delivers only the newest result: a result of an older [submit], or of a
 * [cancel]led one, is dropped (a copy of the ebook reader's `SearchRunner`, ADR 0192, without its `attached` check:
 * closing the panel cancels). [onFx] runs a block on the FX thread; the methods are called on the FX thread, only the
 * work runs on [executor].
 */
internal class SearchRunner(
    private val executor: Executor,
    private val onFx: (Runnable) -> Unit,
) {
    private var generation = 0

    fun <T> submit(
        work: () -> T,
        deliver: (T) -> Unit,
    ) {
        val mine = ++generation
        executor.execute {
            val result = work()
            onFx { if (mine == generation) deliver(result) }
        }
    }

    /** Drops the result of every search submitted so far. */
    fun cancel() {
        generation++
    }
}
