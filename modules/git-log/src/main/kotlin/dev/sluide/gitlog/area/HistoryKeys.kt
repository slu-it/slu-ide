package dev.sluide.gitlog.area

import dev.sluide.git.GitChange
import dev.sluide.git.GitChangedFile
import dev.sluide.git.GitCommit
import dev.sluide.gitlog.history.FileHistoryKey
import java.nio.file.Path

/**
 * The history of [file], a file below the repository at [root] (a folder if [isDirectory]), from `HEAD` on; `null` for
 * the repository itself, a folder and files outside the repository.
 */
internal fun historyKey(
    root: Path,
    file: Path,
    isDirectory: Boolean,
): FileHistoryKey? {
    val relative =
        try {
            root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
        } catch (_: IllegalArgumentException) {
            return null
        }
    if (relative.toString().isEmpty() || relative.startsWith("..") || isDirectory) return null
    return FileHistoryKey(relative.joinToString("/"))
}

/**
 * The history of [file], one of the files [commit] changed, up to that commit: of its path there from the commit on;
 * for a deleted file of its old path from the commit's first parent on, `null` if the commit has none.
 */
internal fun historyKeyUpTo(
    commit: GitCommit,
    file: GitChangedFile,
): FileHistoryKey? =
    if (file.change == GitChange.DELETED) {
        commit.parents.firstOrNull()?.let { FileHistoryKey(file.path, it) }
    } else {
        FileHistoryKey(file.path, commit.hash)
    }
