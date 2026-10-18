package dev.sluide.gitlog

import dev.sluide.git.GitChange
import dev.sluide.git.GitHunk
import java.nio.file.Path

/**
 * A file as it differs between a commit and the working tree, for the IDE to show side by side (ADR 0044): [original]
 * is the file at [commit] (at [oldPath] if renamed; empty if added), [current] the file's text in the working tree as
 * read (empty if deleted), [hunks] the changed lines between them. [label] names the commit as the user chose it
 * (`feature`, `HEAD`, a short hash). [path] is relative to the repository at [root], `/`-separated.
 */
data class WorkingTreeFileDiff(
    val root: Path,
    val path: String,
    val oldPath: String?,
    val change: GitChange,
    val commit: String,
    val label: String,
    val original: String,
    val current: String,
    val hunks: List<GitHunk>,
)
