package dev.sluide.git

/** What a rebase, or continuing, skipping or aborting one, gave (ADR 0063). */
sealed interface GitRebaseResult {
    /**
     * The rebase is done (or aborted); [head] is the new `HEAD` (a full hash), `null` if it could not be read.
     * [stashKept] if git stashed local changes (`--autostash`, ADR 0106) and could not apply them without conflicts:
     * they are kept as a stash entry, the files have the conflicts of applying them.
     */
    data class Done(
        val head: String?,
        val stashKept: Boolean = false,
    ) : GitRebaseResult

    /** Nothing was run: what the branch is to be rebased onto is already contained in it. */
    data object UpToDate : GitRebaseResult

    /**
     * Git stopped and left the rebase for the user ([pending]), at a conflict or for another reason (a hook, untracked
     * files in the way), with git's [message].
     */
    data class Stopped(
        val pending: GitPendingRebase,
        val message: String,
    ) : GitRebaseResult

    /** Continuing was refused without running git: conflict markers are left in these [paths]. */
    data class MarkersLeft(
        val paths: List<String>,
    ) : GitRebaseResult

    /**
     * Continuing needs a confirmation first, nothing was done: these conflicted [files] cannot be checked for conflict
     * markers and would be committed as they are on disk.
     */
    data class Unchecked(
        val files: List<GitUncheckedConflict>,
    ) : GitRebaseResult

    /** Git failed (or was not run), with its [message]; whatever is left is as git left it. */
    data class Failed(
        val message: String,
    ) : GitRebaseResult
}
