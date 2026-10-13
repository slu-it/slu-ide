package dev.sluide.ide

import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.TextDiff
import dev.sluide.git.GitDiff
import dev.sluide.git.GitStatus
import dev.sluide.git.GitStatusService
import java.nio.file.Path

/**
 * The diff of [file] against `HEAD` to show in the editor, if it is modified (ADR 0015); added and unversioned files
 * are new as a whole and are shown normally.
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
