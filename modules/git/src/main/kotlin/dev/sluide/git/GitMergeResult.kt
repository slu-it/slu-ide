package dev.sluide.git

/** How a branch is merged into the current one (ADR 0105); each overrides `merge.ff` of the configuration. */
enum class GitMergeMode(
    internal val arguments: List<String>,
) {
    /** Fast-forwards if the current branch is behind, else creates a merge commit (`--ff`). */
    FAST_FORWARD(listOf("--ff")),

    /** Always creates a merge commit (`--no-ff`). */
    MERGE_COMMIT(listOf("--no-ff")),

    /** Only fast-forwards; git refuses otherwise (`--ff-only`). */
    FAST_FORWARD_ONLY(listOf("--ff-only")),

    /**
     * Stages the changes without a commit (`--squash`); `--ff` before it, as git refuses `--squash` with the `--no-ff`
     * that `merge.ff=false` would give. Never with `--autostash` ([GitMerges.merge]).
     */
    SQUASH(listOf("--ff", "--squash")),
}

/** What a merge, or continuing or aborting one, gave (ADR 0105). */
sealed interface GitMergeResult {
    /**
     * The merge is done (or aborted); [head] is `HEAD` afterwards (a full hash, `null` if it could not be read).
     * [fastForward] if the branch was fast-forwarded, [squashed] if the changes were only staged (`--squash`), then
     * with the [conflicts] git left for the user (no merge is in progress after a squash). [stashKept] if git stashed
     * local changes (`--autostash`) and could not apply them without conflicts: they are kept as a stash entry, the
     * files have the conflicts of applying them.
     */
    data class Done(
        val head: String?,
        val fastForward: Boolean = false,
        val squashed: Boolean = false,
        val conflicts: List<String> = emptyList(),
        val stashKept: Boolean = false,
    ) : GitMergeResult

    /** Nothing was run: the branch to merge is already contained in the current one. */
    data object UpToDate : GitMergeResult

    /**
     * Git stopped and left the merge for the user ([pending]), at conflicts or for another reason (a hook), with git's
     * [message]. Local changes git stashed stay stashed (`MERGE_AUTOSTASH`) until the merge is continued or aborted.
     */
    data class Stopped(
        val pending: GitPendingMerge,
        val message: String,
    ) : GitMergeResult

    /** Continuing was refused without running git: conflict markers are left in these [paths]. */
    data class MarkersLeft(
        val paths: List<String>,
    ) : GitMergeResult

    /**
     * Continuing needs a confirmation first, nothing was done: these conflicted [files] cannot be checked for conflict
     * markers and would be committed as they are on disk.
     */
    data class Unchecked(
        val files: List<GitUncheckedConflict>,
    ) : GitMergeResult

    /** Git failed (or was not run), with its [message]; whatever is left is as git left it. */
    data class Failed(
        val message: String,
    ) : GitMergeResult
}

/**
 * The message git gives a merge of [ref] (a full refname of a local or remote branch) into [branch] (short name), as
 * `git merge` writes it without `merge.log` and with the default `merge.suppressDest` of git 2.53: "Merge branch
 * 'feature'" or "Merge remote-tracking branch 'origin/main'", followed by " into <branch>" unless [branch] is `main` or
 * `master`.
 */
fun defaultMergeMessage(
    ref: String,
    branch: String,
): String {
    val what =
        if (ref.startsWith(REMOTES)) {
            "remote-tracking branch '${ref.removePrefix(REMOTES)}'"
        } else {
            "branch '${ref.removePrefix(HEADS)}'"
        }
    val into = if (branch == "main" || branch == "master") "" else " into $branch"
    return "Merge $what$into"
}

private const val HEADS = "refs/heads/"
private const val REMOTES = "refs/remotes/"
