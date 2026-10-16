package dev.sluide.git

/**
 * What a repository has checked out: [branch] is `null` when `HEAD` is detached, [commit] the full object name or
 * `null` in a repository without commits yet.
 */
data class GitHead(
    val branch: String?,
    val commit: String?,
) {
    /** The first 7 characters of [commit]. */
    val shortCommit: String? get() = commit?.take(7)
}
