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

/**
 * Reads the output of `git diff-tree -r -z --name-status`: a status (`M`, `A`, `D`, `T`, or `R`/`C` with a score
 * such as `R100`) and a path, both NUL-terminated; renames and copies have the old and the new path. Paths are not
 * quoted with `-z`. Other statuses (`U`, `X`) count as modified; an incomplete record at the end is dropped.
 */
internal object GitNameStatusParser {
    fun parse(output: String): List<GitChangedFile> {
        val fields = output.split('\u0000')
        val files = mutableListOf<GitChangedFile>()
        var index = 0
        while (index + 1 < fields.size) {
            val status = fields[index]
            if (status.isEmpty()) break
            val change = changeOf(status[0])
            val paths = if (change == GitChange.RENAMED || change == GitChange.COPIED) 2 else 1
            val record = fields.subList(index + 1, minOf(fields.size, index + 1 + paths))
            if (record.size < paths || record.any(String::isEmpty)) break
            files += if (paths == 2) GitChangedFile(change, record[1], record[0]) else GitChangedFile(change, record[0])
            index += 1 + paths
        }
        return files
    }

    private fun changeOf(status: Char) =
        when (status) {
            'A' -> GitChange.ADDED
            'D' -> GitChange.DELETED
            'R' -> GitChange.RENAMED
            'C' -> GitChange.COPIED
            'T' -> GitChange.TYPE_CHANGED
            else -> GitChange.MODIFIED
        }
}
