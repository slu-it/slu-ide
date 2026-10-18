package dev.sluide.git

/**
 * How the refs of a remote and the tags changed with a fetch (ADR 0043): its remote branches new, moved and deleted
 * (pruned), and tags new, moved and deleted (only with the user's `fetch.pruneTags`).
 */
data class GitFetchChanges(
    val newBranches: Int = 0,
    val updatedBranches: Int = 0,
    val prunedBranches: Int = 0,
    val newTags: Int = 0,
    val updatedTags: Int = 0,
    val prunedTags: Int = 0,
) {
    val none: Boolean get() = this == GitFetchChanges()

    operator fun plus(other: GitFetchChanges) =
        GitFetchChanges(
            newBranches + other.newBranches,
            updatedBranches + other.updatedBranches,
            prunedBranches + other.prunedBranches,
            newTags + other.newTags,
            updatedTags + other.updatedTags,
            prunedTags + other.prunedTags,
        )
}

/**
 * What fetching a remote gave: git's [result] (a failure may have updated some refs all the same, e.g. when git refuses
 * to move an existing tag), whether it was [cancelled], how the refs [changed][changes] (`null` if that could not be
 * read), and whether it was [pruned] (not if its fetch refspecs write outside `refs/remotes/`, see [GitRemotes.fetch]).
 */
data class GitFetchResult(
    val result: GitResult,
    val cancelled: Boolean = false,
    val changes: GitFetchChanges? = null,
    val pruned: Boolean = true,
)
