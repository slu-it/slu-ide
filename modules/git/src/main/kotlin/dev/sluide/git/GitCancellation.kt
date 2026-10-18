package dev.sluide.git

import java.util.concurrent.CompletableFuture

/**
 * Stops a git command that may be stopped halfway, a fetch (ADR 0043), from any thread: [cancel] has git and what it
 * started stopped as a timeout would (SIGTERM, killed after 2 s). Once cancelled, it stays cancelled; a command that
 * has not started yet does not start.
 */
class GitCancellation {
    /** Completed by [cancel]; the runner of git waits for it besides git's end. */
    internal val signal = CompletableFuture<Unit>()

    val cancelled: Boolean get() = signal.isDone

    fun cancel() {
        signal.complete(Unit)
    }
}
