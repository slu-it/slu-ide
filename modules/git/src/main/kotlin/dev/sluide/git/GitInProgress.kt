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

/** Another [operation] in progress, which Thoreau leaves to the terminal. */
data class GitPendingOperation(
    val operation: GitOperation,
) : GitInProgress

/** What git can be in the middle of besides a single cherry-pick or revert. */
enum class GitOperation {
    MERGE,
    REBASE,

    /** `git am`, applying patches from mails. */
    AM,

    /** A cherry-pick of several commits (`git cherry-pick A..B`), its state in `sequencer`. */
    CHERRY_PICK_SEQUENCE,

    /** A revert of several commits. */
    REVERT_SEQUENCE,

    /** A sequence whose kind could not be read. */
    SEQUENCE,
}
