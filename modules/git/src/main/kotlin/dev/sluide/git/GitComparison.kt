package dev.sluide.git

/**
 * How many commits a ref has that `HEAD` has not ([refOnly]), and `HEAD` has that the ref has not ([headOnly]), as
 * the git log's comparison mode shows them (ADR 0044).
 */
data class GitComparisonCounts(
    val refOnly: Int,
    val headOnly: Int,
)

/** The tracked files that differ between a commit and the working tree (ADR 0044). */
sealed interface GitWorkingTreeChanges {
    /**
     * The [files] that differ between [commit] (the full object name the revision named) and the working tree: added
     * ones are in the working tree but not in the commit, deleted ones the other way round.
     */
    data class Files(
        val commit: String,
        val files: List<GitChangedFile>,
    ) : GitWorkingTreeChanges

    /** The revision names no commit (anymore), e.g. a deleted branch. */
    data object Missing : GitWorkingTreeChanges
}
