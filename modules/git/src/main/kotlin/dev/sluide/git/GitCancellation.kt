package dev.sluide.git

/**
 * Stops a git command from any thread: a write that may be stopped halfway, e.g. a fetch (ADR 0043), or a read whose
 * answer is no longer wanted, e.g. a diff overtaken by a newer request (ADR 0062). [cancel] has git and what it started
 * stopped as a timeout would (a write gets SIGTERM and is killed after 2 s, a read is killed). Once cancelled, it stays
 * cancelled; a command that has not started yet does not start. The runner of git looks at it while it waits for git.
 */
class GitCancellation {
    @Volatile
    var cancelled: Boolean = false
        private set

    fun cancel() {
        cancelled = true
    }
}
