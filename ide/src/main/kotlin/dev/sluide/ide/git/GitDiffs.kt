package dev.sluide.ide.git

import dev.sluide.fileviewers.LineChange
import dev.sluide.fileviewers.TextDiff
import dev.sluide.git.GitDiff
import dev.sluide.git.GitStatus
import dev.sluide.git.GitStatusService
import java.nio.file.Path

/**
 * Gives [done] the diff of [file] against `HEAD` to mark in the editor and show on demand, if it is modified (ADRs
 * 0015, 0021); added and unversioned files are new as a whole and have none. Other files get `null` at once, on the
 * calling thread; for modified ones git runs on a thread of its own and [done] is called on the JavaFX thread once it
 * has answered (ADR 0062).
 */
internal fun GitStatusService.textDiff(
    file: Path,
    done: (TextDiff?) -> Unit,
) {
    if (snapshot.get().fileStatus(file.toAbsolutePath().normalize()) != GitStatus.MODIFIED) {
        // An answer for an older request, while it was modified, would come after this one.
        cancelDiff(file)
        done(null)
        return
    }
    diffAsync(file) { done(it?.toTextDiff()) }
}

private fun GitDiff.toTextDiff() =
    TextDiff(
        original,
        hunks.map { LineChange(it.original, it.current) },
        originalTitle = "HEAD",
        currentTitle = "Working Tree",
    )
