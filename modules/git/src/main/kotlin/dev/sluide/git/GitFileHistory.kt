package dev.sluide.git

/**
 * A commit in the history of a file (ADR 0046) and how it changed the file there: [file] has the file's path at that
 * commit (before it for a deletion) and, for a rename or copy, the path it came from.
 */
data class GitFileHistoryEntry(
    val commit: GitCommit,
    val file: GitChangedFile,
)

/**
 * One page of the history of a file: up to the asked number of [entries], newest first. [complete] means there are no
 * more after them; a first page that is empty and complete is a file without commits (untracked, or no `HEAD`).
 */
data class GitFileHistoryPage(
    val entries: List<GitFileHistoryEntry>,
    val complete: Boolean,
)
