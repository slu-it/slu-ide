package dev.sluide.git

/** A file's content at `HEAD` ([original]) and the line ranges in which the working tree differs from it. */
data class GitDiff(
    val original: String,
    val hunks: List<GitHunk>,
)

/**
 * One changed block: the lines [original] at `HEAD` were replaced by the lines [current] in the working tree. Both are
 * 0-based; an empty range starts at the line before which lines were added or removed (the line count if at the end).
 */
data class GitHunk(
    val original: IntRange,
    val current: IntRange,
)
