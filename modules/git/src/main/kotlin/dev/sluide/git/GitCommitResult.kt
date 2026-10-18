package dev.sluide.git

/** What [GitCommits.commit] gave: git's [result], and on success the new `HEAD` (a full hash) as [commit]. */
data class GitCommitResult(
    val result: GitResult,
    val commit: String?,
)
