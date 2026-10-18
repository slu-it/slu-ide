package dev.sluide.ide.git

import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.TextDiff
import dev.sluide.git.GitDiff
import dev.sluide.git.GitStatus
import dev.sluide.git.GitStatusService
import java.nio.file.Path

/**
 * The diff of [file] against `HEAD` to mark in the editor and show on demand, if it is modified (ADRs 0015, 0021);
 * added and unversioned files are new as a whole and have none.
 */
internal fun GitStatusService.textDiff(file: Path): TextDiff? {
    if (snapshot.get().fileStatus(file.toAbsolutePath().normalize()) != GitStatus.MODIFIED) return null
    return diff(file)?.toTextDiff()
}

private fun GitDiff.toTextDiff() =
    TextDiff(
        original,
        hunks.map { LineChange(it.original, it.current) },
        originalTitle = "HEAD",
        currentTitle = "Working Tree",
    )
