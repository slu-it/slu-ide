package dev.sluide.git

/** The commit a local branch points at, as far as it could be read. */
sealed interface GitBranchTip {
    /** There is no such branch. */
    data object Missing : GitBranchTip

    /** The branch points at [commit] (a full hash). */
    data class At(
        val commit: String,
    ) : GitBranchTip

    /** Whether the branch exists and where it points could not be read (git failed). */
    data object Unknown : GitBranchTip
}
