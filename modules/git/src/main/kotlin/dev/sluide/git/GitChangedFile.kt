package dev.sluide.git

/** How a file changed in a commit (ADR 0038). */
enum class GitChange { ADDED, MODIFIED, DELETED, RENAMED, COPIED, TYPE_CHANGED }

/**
 * A file changed in a commit (ADR 0038): its [path] after the commit (before it for a deleted file), `/`-separated
 * and relative to the repository's root, and for renamed and copied files the [oldPath] it came from.
 */
data class GitChangedFile(
    val change: GitChange,
    val path: String,
    val oldPath: String? = null,
)

/**
 * The difference a commit made to one file (ADR 0038): both texts with git's hunks ([Text]), or why there are none
 * to show ([Binary], [TooLarge], [Submodule]).
 */
sealed interface GitFileDiff {
    /**
     * The file's [original] text in the parent (empty if it was added) and [current] text in the commit (empty if it
     * was deleted), as stored, without filters; [hunks] are the changed lines.
     */
    data class Text(
        val original: String,
        val current: String,
        val hunks: List<GitHunk>,
    ) : GitFileDiff

    /** Either side has a NUL byte in its first 8000 bytes, git's own test for binary files. */
    data object Binary : GitFileDiff

    /** Either side is larger than the limit (5 MB). */
    data object TooLarge : GitFileDiff

    /**
     * The file is a submodule (a gitlink) on at least one side: the commits it pointed at before ([old]) and after
     * ([new]), `null` where it was no submodule (added, deleted, or a file before or after).
     */
    data class Submodule(
        val old: String?,
        val new: String?,
    ) : GitFileDiff
}
