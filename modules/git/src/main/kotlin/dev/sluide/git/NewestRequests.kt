package dev.sluide.git

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * The newest request per key of an asynchronous git read (ADR 0062), run one after another on [executor]: an older
 * request for a key does not start, its running git is stopped ([GitCancellation]) and its result is dropped, as is
 * the result of a request whose repository is no longer followed. Results go through [dispatch]; every call is on the
 * thread [dispatch] runs on.
 */
internal class NewestRequests<K : Any>(
    private val executor: Executor,
    private val dispatch: (Runnable) -> Unit,
) {
    private val requests = HashMap<K, GitCancellation>()

    /** Runs [work] for [key], then gives its result to [done] unless it was replaced or [followed] is false by then. */
    fun <T> submit(
        key: K,
        followed: () -> Boolean,
        work: (GitCancellation) -> T,
        done: (T) -> Unit,
    ) {
        val request = GitCancellation()
        requests.put(key, request)?.cancel()
        try {
            executor.execute {
                if (request.cancelled) return@execute
                val result = work(request)
                dispatch(
                    Runnable {
                        if (requests[key] !== request) return@Runnable
                        requests.remove(key)
                        if (followed()) done(result)
                    },
                )
            }
        } catch (_: RejectedExecutionException) {
            // Closed.
            requests.remove(key, request)
        }
    }

    /** Drops the request for [key], if any; its git is stopped. */
    fun cancel(key: K) {
        requests.remove(key)?.cancel()
    }
}
