package dev.sluide.git

import java.util.concurrent.CompletableFuture

/**
 * Stops a git command from any thread: a write that may be stopped halfway, e.g. a fetch (ADR 0043), or a read whose
 * answer is no longer wanted, e.g. a diff overtaken by a newer request (ADR 0062). [cancel] has git and what it started
 * stopped as a timeout would (a write gets SIGTERM and is killed after 2 s, a read is killed). Once cancelled, it stays
 * cancelled; a command that has not started yet does not start.
 */
class GitCancellation {
    /** Completed by [cancel]; the runner of git waits for it besides git's end. */
    internal val signal = CompletableFuture<Unit>()

    val cancelled: Boolean get() = signal.isDone

    fun cancel() {
        signal.complete(Unit)
    }
}
