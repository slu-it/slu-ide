package dev.sluide.ide.git

import dev.sluide.fileexplorer.FileMarker
import dev.sluide.fileexplorer.FileMarkers
import dev.sluide.git.GitStatus
import dev.sluide.git.GitStatusSnapshot
import java.nio.file.Path

/**
 * The explorer markers for the git status of a file or folder (`directory`). A file, and an ignored folder, get the
 * colour of their status as name colour; any other folder gets a bubble per status found below it, in [GitStatus]
 * order.
 */
internal fun GitStatusSnapshot.markers(
    path: Path,
    directory: Boolean,
): FileMarkers {
    if (!directory) return FileMarkers(name = fileStatus(path)?.marker)
    val statuses = folderStatuses(path)
    if (statuses == setOf(GitStatus.IGNORED)) return FileMarkers(name = FileMarker.IGNORED)
    return FileMarkers(bubbles = statuses.map { it.marker })
}

private val GitStatus.marker: FileMarker
    get() =
        when (this) {
            GitStatus.UNVERSIONED -> FileMarker.UNVERSIONED
            GitStatus.ADDED -> FileMarker.ADDED
            GitStatus.MODIFIED -> FileMarker.MODIFIED
            GitStatus.IGNORED -> FileMarker.IGNORED
        }
