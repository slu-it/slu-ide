package dev.sluide.ide.git

import dev.sluide.fileexplorer.FileExplorerView
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

/**
 * Whether git ignores the folder [path] as a whole (ADR 0158); `null` outside the repository or without one, where git
 * has no say.
 */
internal fun GitStatusSnapshot.ignoresFolder(path: Path): Boolean? {
    val root = root
    if (root == null || !path.startsWith(root)) return null
    return folderStatuses(path) == setOf(GitStatus.IGNORED)
}

/** Lets the explorer show git's markers and ask it which folders it ignores, from the current [snapshot]. */
internal fun FileExplorerView.showGitStatus(snapshot: () -> GitStatusSnapshot) {
    markers = { path, directory -> snapshot().markers(path, directory) }
    ignoredByGit = { snapshot().ignoresFolder(it) }
}

/** Tells the explorer that the git status changed: generated folders git ignores or tracks, then the markers. */
internal fun FileExplorerView.gitStatusChanged() {
    gitIgnoresChanged()
    refreshMarkers()
}

private val GitStatus.marker: FileMarker
    get() =
        when (this) {
            GitStatus.UNVERSIONED -> FileMarker.UNVERSIONED
            GitStatus.ADDED -> FileMarker.ADDED
            GitStatus.MODIFIED -> FileMarker.MODIFIED
            GitStatus.IGNORED -> FileMarker.IGNORED
        }
