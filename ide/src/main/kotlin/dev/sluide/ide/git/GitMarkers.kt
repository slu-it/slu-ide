package dev.sluide.ide.git

import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.fileexplorer.FileMarker
import dev.sluide.fileexplorer.FileMarkers
import dev.sluide.git.GitStatus
import dev.sluide.git.GitStatusSnapshot
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Duration

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

/** Margin for the coarse modification times of some file systems when comparing with [GitStatusSnapshot.startedAt]. */
private val MTIME_MARGIN: Duration = Duration.ofSeconds(2)

/**
 * Whether git tracks the file [path] (ADR 0163): false when it is untracked or ignored, also inside such a folder;
 * `null` where git has no say: outside the repository or without one, inside a nested repository (a folder between
 * the file and the root has a `.git` entry, the status does not cover it), and for a file the status cannot know yet
 * (absent from it, but modified within [MTIME_MARGIN] before the status run started or later, or with an unreadable
 * modification time).
 */
internal fun GitStatusSnapshot.tracksFile(path: Path): Boolean? {
    val root = root
    if (root == null || !path.startsWith(root)) return null
    val status = fileStatus(path)
    if (status == GitStatus.UNVERSIONED || status == GitStatus.IGNORED) return false
    if (insideNestedRepository(path, root)) return null
    return if (status == null && isNewerThanStatus(path)) null else true
}

private fun insideNestedRepository(
    path: Path,
    root: Path,
): Boolean {
    var folder = path.parent
    while (folder != null && folder != root && folder.startsWith(root)) {
        if (Files.exists(folder.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) return true
        folder = folder.parent
    }
    return false
}

private fun GitStatusSnapshot.isNewerThanStatus(path: Path): Boolean {
    val started = startedAt ?: return false
    val modified =
        try {
            Files.getLastModifiedTime(path).toInstant()
        } catch (_: IOException) {
            return true
        } catch (_: SecurityException) {
            return true
        }
    return modified.isAfter(started.minus(MTIME_MARGIN))
}

/** Lets the explorer show git's markers and ask it which folders it ignores and files it tracks, from the current [snapshot]. */
internal fun FileExplorerView.showGitStatus(snapshot: () -> GitStatusSnapshot) {
    markers = { path, directory -> snapshot().markers(path, directory) }
    ignoredByGit = { snapshot().ignoresFolder(it) }
    trackedByGit = { snapshot().tracksFile(it) }
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
