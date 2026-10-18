package dev.sluide.gitlog

import dev.sluide.git.GitChange
import dev.sluide.git.GitHunk
import java.nio.file.Path

/**
 * A file as a commit changed it, for the IDE to show side by side (ADR 0038): [original] is the file at the
 * commit's first [parent] (at [oldPath] if it was renamed or copied; empty if it was added), [current] at the
 * [commit] (empty if it was deleted), [hunks] the changed lines. [path] is relative to the repository at [root],
 * `/`-separated.
 */
data class CommitFileDiff(
    val root: Path,
    val path: String,
    val oldPath: String?,
    val change: GitChange,
    val commit: String,
    val parent: String?,
    val original: String,
    val current: String,
    val hunks: List<GitHunk>,
)
