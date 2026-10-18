package dev.sluide.git

/** Git's commands that apply a commit's changes: cherry-pick, or revert to undo them. */
enum class GitPick(
    /** The git command: `cherry-pick`, `revert`. */
    val command: String,
) {
    CHERRY_PICK("cherry-pick"),
    REVERT("revert"),
}

/** What a cherry-pick or revert, or continuing one, gave (ADR 0042). */
sealed interface GitPickResult {
    /** A commit was made; [commit] is the new `HEAD` (a full hash), `null` if it could not be read. */
    data class Done(
        val commit: String?,
    ) : GitPickResult

    /**
     * There was nothing to commit: the changes are already there (or already undone). The state git left was cleared
     * (`--skip`, or `--quit` for a revert, which leaves no `REVERT_HEAD`); nothing else changed. [branch] is the short
     * name of the current branch, `null` when `HEAD` is detached or that could not be read.
     */
    data class Empty(
        val branch: String?,
    ) : GitPickResult

    /**
     * Git stopped and left the state for the user: with [conflicts] (paths), or without (e.g. a hook failed), with
     * git's [message].
     */
    data class Stopped(
        val conflicts: List<String>,
        val message: String,
    ) : GitPickResult

    /** Continuing was refused without running git: conflict markers are left in these [paths]. */
    data class MarkersLeft(
        val paths: List<String>,
    ) : GitPickResult

    /**
     * Continuing needs a confirmation first, nothing was done: these conflicted [files] cannot be checked for conflict
     * markers and would be committed as they are on disk.
     */
    data class Unchecked(
        val files: List<GitUncheckedConflict>,
    ) : GitPickResult

    /** Git failed (or was not run), with its [message]; whatever is left is as git left it. */
    data class Failed(
        val message: String,
    ) : GitPickResult
}
