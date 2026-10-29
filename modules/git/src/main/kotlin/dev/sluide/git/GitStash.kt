package dev.sluide.git

/**
 * A stash entry as `git stash list` lists it (ADR 0107): `stash@{<index>}`, newest first from 0, the stash [commit] (a
 * full hash), its committer [time] (seconds since the epoch) and its [message] as git lists it ("On feature: message",
 * "WIP on feature: 1a2b3c4 subject", "On (no branch): …" for one made on a detached `HEAD`).
 */
data class GitStash(
    val index: Int,
    val commit: String,
    val time: Long,
    val message: String,
) {
    /** How git names it: `stash@{0}`. */
    val name: String get() = "stash@{$index}"
}

/**
 * What stashing changes needs to know first (ADR 0107): the current [branch] (short name, `null` when `HEAD` is
 * detached) and why nothing can be stashed now ([busy]: what git is in the middle of, or that this cannot be read;
 * `null` for nothing).
 */
data class GitStashSources(
    val branch: String?,
    val busy: String?,
)

/** What stashing, or popping, applying or dropping a stash gave (ADR 0107). */
sealed interface GitStashResult {
    /** Done: the changes are stashed, the stash applied (and for a pop dropped) or dropped. */
    data object Done : GitStashResult

    /** Nothing was stashed: there were no local changes (to the paths given); `refs/stash` did not move. */
    data object NothingToStash : GitStashResult

    /**
     * Applying the stash stopped at conflicts in these [paths], which keep git's conflict markers and are unmerged in
     * the index; [kept] if the stash is still in the list (git 2.53 keeps it on a pop with conflicts).
     */
    data class Conflicts(
        val paths: List<String>,
        val kept: Boolean,
    ) : GitStashResult

    /**
     * Applying the stash failed after it changed files (git 2.53 writes what it can before it fails): its changes to
     * tracked files were applied ([tracked] `true`), not applied (`false`), or there are none (`null`); its untracked
     * files [restored] (absent before, there now) and those [inTheWay] (on disk before, so not restored); [kept] if
     * the stash is still in the list (git 2.53 keeps it), with git's [message].
     */
    data class Partly(
        val tracked: Boolean?,
        val restored: List<String>,
        val inTheWay: List<String>,
        val kept: Boolean,
        val message: String,
    ) : GitStashResult

    /** Nothing was run: the stash is no longer the one listed (`stash@{n}` names another commit, or none). */
    data object Changed : GitStashResult

    /** Git failed (or was not run), with its [message]; whatever is left is as git left it. */
    data class Failed(
        val message: String,
    ) : GitStashResult
}

/** What marking files with conflicts resolved gave (ADR 0107). */
sealed interface GitResolveResult {
    /** These [paths] were staged; none is unmerged anymore. */
    data class Done(
        val paths: List<String>,
    ) : GitResolveResult

    /** Refused without staging: conflict markers are left in these [paths]. */
    data class MarkersLeft(
        val paths: List<String>,
    ) : GitResolveResult

    /**
     * Needs a confirmation first, nothing was staged: these conflicted [files] cannot be checked for conflict markers
     * and would be staged as they are on disk.
     */
    data class Unchecked(
        val files: List<GitUncheckedConflict>,
    ) : GitResolveResult

    /** Git failed (or was not run), with its [message]. */
    data class Failed(
        val message: String,
    ) : GitResolveResult
}
