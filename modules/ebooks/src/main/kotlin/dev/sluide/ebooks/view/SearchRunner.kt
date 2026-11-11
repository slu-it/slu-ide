package dev.sluide.ebooks.view

import java.util.concurrent.Executor

/**
 * Runs searches off the FX thread and delivers only the newest result: a result of an older [submit], of a
 * [cancel]led one, or one that arrives while not `attached`, is dropped. [onFx] runs a block on the FX thread; the
 * methods are called on the FX thread, only the work runs on [executor].
 */
internal class SearchRunner(
    private val executor: Executor,
    private val onFx: (Runnable) -> Unit,
) {
    private var generation = 0

    fun <T> submit(
        work: () -> T,
        attached: () -> Boolean,
        deliver: (T) -> Unit,
    ) {
        val mine = ++generation
        executor.execute {
            val result = work()
            onFx { if (mine == generation && attached()) deliver(result) }
        }
    }

    /** Drops the result of every search submitted so far. */
    fun cancel() {
        generation++
    }
}
