package dev.sluide.databases.session

import dev.sluide.backgroundtasks.NamedThreads
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException

/**
 * The one thread that touches a JDBC connection: work runs in the order it was given. Once [isClosed] is true, work
 * that has not started completes exceptionally with [DbSessionClosedException]; only [finish] still runs.
 */
internal class ConnectionWorker(
    threadName: String,
    private val isClosed: () -> Boolean,
) {
    private val executor = NamedThreads.single(threadName)
    private val unfinished = ConcurrentHashMap.newKeySet<CompletableFuture<*>>()

    fun <T> submit(work: () -> T): CompletableFuture<T> = enqueue(work) { isClosed() }

    /** Runs [work] even after the close, as the last task, and then ends the thread. */
    fun finish(work: () -> Unit): CompletableFuture<Unit> = enqueue(work) { false }.also { executor.shutdown() }

    /**
     * Completes every future not completed yet exceptionally with [DbSessionClosedException], for a thread that hangs
     * in work its aborted connection did not end; the work's own result comes too late and is dropped.
     */
    fun abandon() {
        unfinished.toList().forEach { it.completeExceptionally(DbSessionClosedException()) }
    }

    private fun <T> enqueue(
        work: () -> T,
        refuse: () -> Boolean,
    ): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        unfinished += future
        future.whenComplete { _, _ -> unfinished -= future }
        try {
            executor.execute {
                if (refuse()) {
                    future.completeExceptionally(DbSessionClosedException())
                } else {
                    try {
                        future.complete(work())
                    } catch (t: Throwable) {
                        future.completeExceptionally(t)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            future.completeExceptionally(DbSessionClosedException())
        }
        return future
    }
}
