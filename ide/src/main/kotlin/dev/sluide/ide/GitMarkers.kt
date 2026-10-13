package dev.sluide.ide

import dev.sluide.fileexplorer.FileMarker
import dev.sluide.git.GitStatus
import dev.sluide.git.GitStatusSnapshot
import java.nio.file.Path

/** The explorer markers for the git status of a file or folder (`directory`): one per status, in [GitStatus] order. */
internal fun GitStatusSnapshot.markers(
    path: Path,
    directory: Boolean,
): List<FileMarker> {
    val statuses = if (directory) folderStatuses(path) else listOfNotNull(fileStatus(path))
    return statuses.map { it.marker }
}

private val GitStatus.marker: FileMarker
    get() =
        when (this) {
            GitStatus.UNVERSIONED -> FileMarker.UNVERSIONED
            GitStatus.ADDED -> FileMarker.ADDED
            GitStatus.MODIFIED -> FileMarker.MODIFIED
            GitStatus.IGNORED -> FileMarker.IGNORED
        }
