package dev.sluide.git

/**
 * One page of the git log: up to the asked number of [commits], newest first. [complete] means there are no more
 * after them; a first page that is empty and complete is a repository without commits yet.
 */
data class GitLogPage(
    val commits: List<GitCommit>,
    val complete: Boolean,
)
