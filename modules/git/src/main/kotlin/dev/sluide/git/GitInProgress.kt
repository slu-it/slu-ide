package dev.sluide.git

/** Something git is in the middle of in a repository (ADR 0042), as `git status` would tell. */
sealed interface GitInProgress

/**
 * A single cherry-pick or revert in progress: git stopped it for the [commit] (a full hash), `CHERRY_PICK_HEAD` or
 * `REVERT_HEAD`, and [conflicts] are the paths still unmerged (relative to the repository), empty once all are staged.
 */
data class GitPendingPick(
    val kind: GitPick,
    val commit: String,
    val conflicts: List<String>,
) : GitInProgress

/**
 * A rebase in progress (ADR 0063), also one started in a terminal (`git rebase`, `git rebase -i`, `--apply`), as far as
 * its state files tell; a field that cannot be read is `null`.
 *
 * [branch] is the short name of the branch being rebased (`head-name`), `null` when the rebase started on a detached
 * `HEAD`; [onto] the full hash it is rebased onto (`onto`); [stopped] the commit git stopped at (`REBASE_HEAD`, also
 * written at an `edit` instruction), `null` where there is none (a stop at a `break` or a failed `exec` instruction);
 * [step] of [total] the position of that commit (`msgnum` of `end` for the merge backend, `next` of `last` for
 * `--apply`); [conflicts] the paths still unmerged, empty once all are staged.
 */
data class GitPendingRebase(
    val branch: String?,
    val onto: String?,
    val stopped: String?,
    val step: Int?,
    val total: Int?,
    val conflicts: List<String>,
) : GitInProgress

/**
 * A merge in progress (ADR 0105), also one started in a terminal or by `git pull`: [heads] are the commits being merged
 * (full hashes, `MERGE_HEAD`; more than one for an octopus merge, empty if the file cannot be read), [message] the
 * first line of the message git prepared (`MERGE_MSG`; `null` if it cannot be read), [conflicts] the paths still
 * unmerged, empty once all are staged.
 */
data class GitPendingMerge(
    val heads: List<String>,
    val message: String?,
    val conflicts: List<String>,
) : GitInProgress

/** Another [operation] in progress, which Thoreau leaves to the terminal. */
data class GitPendingOperation(
    val operation: GitOperation,
) : GitInProgress

/** What git can be in the middle of besides a single cherry-pick, revert, a rebase or a merge. */
enum class GitOperation {
    /** `git am`, applying patches from mails. */
    AM,

    /** A cherry-pick of several commits (`git cherry-pick A..B`), its state in `sequencer`. */
    CHERRY_PICK_SEQUENCE,

    /** A revert of several commits. */
    REVERT_SEQUENCE,

    /** A sequence whose kind could not be read. */
    SEQUENCE,
}
