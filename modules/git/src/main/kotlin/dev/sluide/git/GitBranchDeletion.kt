package dev.sluide.git

/**
 * What deleting branches gave: the [result] of `git branch -d` (or `-D`), and those of the branches asked for that are
 * still there afterwards, e.g. as git refuses to delete a branch that is not fully merged with `-d`.
 */
data class GitBranchDeletion(
    val result: GitResult,
    val notDeleted: List<String>,
)
